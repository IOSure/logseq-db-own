(ns frontend.db.restore
  "Fns for DB restore(from text or sqlite)"
  (:require [cljs-time.core :as t]
            [frontend.db.conn :as db-conn]
            [frontend.persist-db :as persist-db]
            [frontend.state :as state]
            [logseq.db.common.sqlite :as sqlite-common-db]
            [promesa.core :as p]))

(defn restore-graph!
  "Restore db from SQLite"
  [repo & {:as opts}]
  (state/set-state! :graph/loading? true)
  (p/let [start-time (t/now)
          {:keys [schema initial-data] :as data} (persist-db/<fetch-init-data repo opts)
          _ (assert (some? data) "No data found when reloading db")
          conn (try
                 (sqlite-common-db/restore-initial-data initial-data schema)
                 (catch :default e
                   (js/console.error e)
                   (throw e)))
          db-name (db-conn/get-repo-path repo)
          _ (swap! db-conn/conns assoc db-name conn)
          end-time (t/now)]

    (println ::restore-graph! "loads" (count initial-data) "datoms in" (t/in-millis (t/interval start-time end-time)) "ms")

    (state/pub-event! [:graph/restored repo])
    (state/set-state! :graph/loading? false)
    (state/pub-event! [:ui/re-render-root])

    ;; (async/go
    ;;   (async/<! (async/timeout 100))
    ;;   (db-async/<fetch-all-pages repo))
    ))
