(ns frontend.persist-db.browser
  "Browser db persist support, using @logseq/sqlite-wasm.

   This interface uses clj data format as input."
  (:require ["comlink" :as Comlink]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.date :as date]
            [frontend.db.transact :as db-transact]
            [frontend.handler.assets :as assets-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.worker :as worker-handler]
            [frontend.persist-db.protocol :as protocol]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.db :as ldb]
            [promesa.core :as p]))

(defonce *worker state/*db-worker)

(defn- ask-persist-permission!
  []
  (p/let [persistent? (.persist js/navigator.storage)]
    (if persistent?
      (js/console.log "Storage will not be cleared unless from explicit user action")
      (js/console.warn "OPFS storage may be cleared by the browser under storage pressure."))))

(defn- sync-app-state!
  [worker]
  (add-watch state/state
             :sync-worker-state
             (fn [_ _ prev current]
               (let [new-state (cond-> {}
                                 (not= (:git/current-repo prev)
                                       (:git/current-repo current))
                                 (assoc :git/current-repo (:git/current-repo current))
                                 (not= (:config prev) (:config current))
                                 (assoc :config (:config current)))]
                 (when (seq new-state)
                   (worker :thread-api/sync-app-state new-state))))))

(defn get-route-data
  [route-match]
  (when (seq route-match)
    {:to (get-in route-match [:data :name])
     :path-params (:path-params route-match)
     :query-params (:query-params route-match)}))

(defn- sync-ui-state!
  [^js worker]
  (add-watch state/state
             :sync-ui-state
             (fn [_ _ prev current]
               (when-not @(:history/paused? @state/state)
                 (let [f (fn [state]
                           (-> (select-keys state [:ui/sidebar-open? :ui/sidebar-collapsed-blocks :sidebar/blocks])
                               (assoc :route-data (get-route-data (:route-match state)))))
                       old-state (f prev)
                       new-state (f current)]
                   (when (not= new-state old-state)
                     (worker :thread-api/sync-ui-state
                             (state/get-current-repo)
                             {:old-state old-state :new-state new-state})))))))

(defn transact!
  [worker repo tx-data tx-meta]
  (let [;; TODO: a better way to share those information with worker, maybe using the state watcher to notify the worker?
        context {:dev? config/dev?
                 :node-test? util/node-test?
                 :validate-db-options (:dev/validate-db-options (state/get-config))
                 :importing? (:graph/importing @state/state)
                 :date-formatter (state/get-date-formatter)
                 :journal-file-name-format (or (state/get-journal-file-name-format)
                                               date/default-journal-filename-formatter)
                 :export-bullet-indentation (state/get-export-bullet-indentation)
                 :preferred-format (state/get-preferred-format)
                 :journals-directory (config/get-journals-directory)
                 :whiteboards-directory (config/get-whiteboards-directory)
                 :pages-directory (config/get-pages-directory)}]
    (if worker
      (worker :thread-api/transact repo tx-data tx-meta context)
      (notification/show! "Latest change was not saved! Please restart the application." :error))))

(defn- with-write-transit-str
  [p]
  (p/chain p ldb/write-transit-str))

(deftype Main []
  Object
  (readAsset [_this repo asset-block-id asset-type]
    (assets-handler/<read-asset repo asset-block-id asset-type))
  (writeAsset [_this repo asset-block-id asset-type data]
    (assets-handler/<write-asset repo asset-block-id asset-type data))
  (unlinkAsset [_this repo asset-block-id asset-type]
    (assets-handler/<unlink-asset repo asset-block-id asset-type))
  (get-all-asset-file-paths [_this repo]
    (with-write-transit-str
      (assets-handler/<get-all-asset-file-paths repo)))
  (get-asset-file-metadata [_this repo asset-block-id asset-type]
    (with-write-transit-str
      (assets-handler/<get-asset-file-metadata repo asset-block-id asset-type)))
  (rtc-upload-asset [_this repo asset-block-uuid-str asset-type checksum put-url]
    (with-write-transit-str
      (js/Promise.
       (assets-handler/new-task--rtc-upload-asset repo asset-block-uuid-str asset-type checksum put-url))))
  (rtc-download-asset [_this repo asset-block-uuid-str asset-type get-url]
    (with-write-transit-str
      (js/Promise.
       (assets-handler/new-task--rtc-download-asset repo asset-block-uuid-str asset-type get-url))))
  (testFn [_this]
    (prn :debug :works)))

(defn start-db-worker!
  []
  (when-not util/node-test?
    (let [worker-url (if (util/electron?)
                       "js/db-worker.js"
                       "static/js/db-worker.js")
          worker (js/Worker. (str worker-url "?electron=" (util/electron?) "&publishing=" config/publishing?))
          wrapped-worker* (Comlink/wrap worker)
          wrapped-worker (fn [qkw & args]
                           (p/chain
                            (.remoteInvoke ^js wrapped-worker*
                                           (str (namespace qkw) "/" (name qkw))
                                           (ldb/write-transit-str args))
                            ldb/read-transit-str))
          t1 (util/time-ms)]
      (Comlink/expose (Main.) worker)
      (worker-handler/handle-message! worker wrapped-worker)
      (reset! *worker wrapped-worker)
      (-> (p/let [_ (wrapped-worker :thread-api/init config/RTC-WS-URL)
                  _ (js/console.debug (str "debug: init worker spent: " (- (util/time-ms) t1) "ms"))
                  _ (wrapped-worker :thread-api/sync-app-state
                                    {:git/current-repo (state/get-current-repo)
                                     :config (:config @state/state)})
                  _ (sync-app-state! wrapped-worker)
                  _ (sync-ui-state! wrapped-worker)
                  _ (ask-persist-permission!)
                  _ (state/pub-event! [:graph/sync-context])]
            (ldb/register-transact-fn!
             (fn worker-transact!
               [repo tx-data tx-meta]
               (db-transact/transact (partial transact! wrapped-worker)
                                     (if (string? repo) repo (state/get-current-repo))
                                     tx-data
                                     tx-meta)))
            (db-transact/listen-for-requests))
          (p/catch (fn [error]
                     (prn :debug "Can't init SQLite wasm")
                     (js/console.error error)
                     (notification/show! "It seems that OPFS is not supported on this browser, please upgrade this browser to the latest version or use another browser." :error)))))))

(defn <export-db!
  [repo data]
  (cond
    (util/electron?)
    (ipc/ipc :db-export repo data)

    ;; TODO: browser nfs-supported? auto backup

    ;;
    :else
    nil))

(defn- sqlite-error-handler
  [error]
  (if (= "NoModificationAllowedError"  (.-name error))
    (do
      (js/console.error error)
      (state/pub-event! [:show/multiple-tabs-error-dialog]))
    (notification/show! [:div (str "SQLiteDB error: " error)] :error)))

(defrecord InBrowser []
  protocol/PersistentDB
  (<new [_this repo opts]
    (when-let [worker @*worker]
      (worker :thread-api/create-or-open-db repo opts)))

  (<list-db [_this]
    (when-let [worker @*worker]
      (-> (worker :thread-api/list-db)
          (p/catch sqlite-error-handler))))

  (<unsafe-delete [_this repo]
    (when-let [worker @*worker]
      (worker :thread-api/unsafe-unlink-db repo)))

  (<release-access-handles [_this repo]
    (when-let [worker @*worker]
      (worker :thread-api/release-access-handles repo)))

  (<fetch-initial-data [_this repo opts]
    (when-let [^js worker @*worker]
      (-> (p/let [db-exists? (worker :thread-api/db-exists repo)
                  disk-db-data (when-not db-exists? (ipc/ipc :db-get repo))
                  _ (when disk-db-data
                      (worker :thread-api/import-db repo disk-db-data))
                  _ (worker :thread-api/create-or-open-db repo opts)]
            (worker :thread-api/get-initial-data repo))
          (p/catch sqlite-error-handler))))

  (<export-db [_this repo opts]
    (when-let [worker @*worker]
      (-> (p/let [data (worker :thread-api/export-db repo)]
            (when data
              (if (:return-data? opts)
                data
                (<export-db! repo data))))
          (p/catch (fn [error]
                     (prn :debug :save-db-error repo)
                     (js/console.error error)
                     (notification/show! [:div (str "SQLiteDB save error: " error)] :error) {})))))

  (<import-db [_this repo data]
    (when-let [worker @*worker]
      (-> (worker :thread-api/import-db repo data)
          (p/catch (fn [error]
                     (prn :debug :import-db-error repo)
                     (js/console.error error)
                     (notification/show! [:div (str "SQLiteDB import error: " error)] :error) {}))))))

(comment
  (defn clean-all-dbs!
    []
    (when-let [worker @*worker]
      (worker :general/dangerousRemoveAllDbs)
      (state/set-current-repo! nil))))
