(ns logseq.db
  "Main namespace for public db fns. For DB and file graphs.
   For shared file graph only fns, use logseq.graph-parser.db"
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [datascript.core :as d]
            [datascript.impl.entity :as de]
            [logseq.common.util :as common-util]
            [logseq.common.util.namespace :as ns-util]
            [logseq.common.util.page-ref :as page-ref]
            [logseq.common.uuid :as common-uuid]
            [logseq.db.common.delete-blocks :as delete-blocks] ;; Load entity extensions
            [logseq.db.common.entity-util :as common-entity-util]
            [logseq.db.common.sqlite :as sqlite-common-db]
            [logseq.db.frontend.class :as db-class]
            [logseq.db.frontend.entity-plus :as entity-plus]
            [logseq.db.frontend.entity-util :as entity-util]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.rules :as rules]
            [logseq.db.sqlite.util :as sqlite-util])
  (:refer-clojure :exclude [object?]))

(defonce *transact-fn (atom nil))
(defn register-transact-fn!
  [f]
  (when f (reset! *transact-fn f)))

(defn- remove-temp-block-data
  [tx-data]
  (let [remove-block-temp-f (fn [m]
                              (->> (remove (fn [[k _v]] (= "block.temp" (namespace k))) m)
                                   (into {})))]
    (map (fn [m]
           (if (map? m)
             (cond->
              (remove-block-temp-f m)
               (and (seq (:block/refs m))
                    (every? map? (:block/refs m)))
               (update :block/refs (fn [refs] (map remove-block-temp-f refs))))
             m))
         tx-data)))

(defn assert-no-entities
  [tx-data]
  (walk/prewalk
   (fn [f]
     (if (de/entity? f)
       (throw (ex-info "ldb/transact! doesn't support Entity"
                       {:entity f
                        :tx-data tx-data}))
       f))
   tx-data))

(defn transact!
  "`repo-or-conn`: repo for UI thread and conn for worker/node"
  ([repo-or-conn tx-data]
   (transact! repo-or-conn tx-data nil))
  ([repo-or-conn tx-data tx-meta]
   (when (or (exists? js/process)
             (and (exists? js/goog) js/goog.DEBUG))
     (assert-no-entities tx-data))
   (let [tx-data (map (fn [m]
                        (if (map? m)
                          (dissoc m :block/children :block/meta :block/top? :block/bottom? :block/anchor
                                  :block/level :block/container :db/other-tx
                                  :block/unordered)
                          m)) tx-data)
         tx-data (->> (remove-temp-block-data tx-data)
                      (common-util/fast-remove-nils)
                      (remove empty?))
         delete-blocks-tx (when-not (string? repo-or-conn)
                            (delete-blocks/update-refs-history-and-macros @repo-or-conn tx-data tx-meta))
         tx-data (concat tx-data delete-blocks-tx)]

     ;; Ensure worker can handle the request sequentially (one by one)
     ;; Because UI assumes that the in-memory db has all the data except the last one transaction
     (when (seq tx-data)

       ;; (prn :debug :transact :sync? (= d/transact! (or @*transact-fn d/transact!)) :tx-meta tx-meta)
       ;; (cljs.pprint/pprint tx-data)
       ;; (js/console.trace)

       (let [f (or @*transact-fn d/transact!)]
         (try
           (f repo-or-conn tx-data tx-meta)
           (catch :default e
             (js/console.trace)
             (prn :debug-tx-data tx-data)
             (throw e))))))))

(def page? common-entity-util/page?)
(def internal-page? entity-util/internal-page?)
(def class? entity-util/class?)
(def property? entity-util/property?)
(def closed-value? entity-util/closed-value?)
(def whiteboard? common-entity-util/whiteboard?)
(def journal? common-entity-util/journal?)
(def hidden? entity-util/hidden?)
(def object? entity-util/object?)
(def asset? entity-util/asset?)
(def public-built-in-property? db-property/public-built-in-property?)
(def get-entity-types entity-util/get-entity-types)
(def internal-tags db-class/internal-tags)
(def private-tags db-class/private-tags)
(def hidden-tags db-class/hidden-tags)

(defn sort-by-order
  [blocks]
  (sort-by :block/order blocks))

(defn- get-block-and-children-aux
  [entity & {:keys [include-property-block?]
             :or {include-property-block? false}
             :as opts}]
  (if-let [children (sort-by-order
                     (if include-property-block?
                       (:block/_raw-parent entity)
                       (:block/_parent entity)))]
    (cons entity (mapcat #(get-block-and-children-aux % opts) children))
    [entity]))

(defn get-block-and-children
  [db block-uuid & {:as opts}]
  (when-let [e (d/entity db [:block/uuid block-uuid])]
    (get-block-and-children-aux e opts)))

(defn get-page-blocks
  "Return blocks of the designated page, without using cache.
   page-id - eid"
  [db page-id & {:keys [pull-keys]
                 :or {pull-keys '[*]}}]
  (when page-id
    (let [datoms (d/datoms db :avet :block/page page-id)
          block-eids (mapv :e datoms)]
      (d/pull-many db pull-keys block-eids))))

(defn get-page-blocks-count
  [db page-id]
  (count (d/datoms db :avet :block/page page-id)))

(defn- get-block-children-or-property-children
  [block parent]
  (let [from-property (:logseq.property/created-from-property block)
        closed-property (:block/closed-value-property block)]
    (sort-by-order (cond
                     closed-property
                     (:property/closed-values closed-property)

                     from-property
                     (filter (fn [e]
                               (= (:db/id (:logseq.property/created-from-property e))
                                  (:db/id from-property)))
                             (:block/_raw-parent parent))

                     :else
                     (:block/_parent parent)))))

(defn get-right-sibling
  [block]
  (assert (or (de/entity? block) (nil? block)))
  (when-let [parent (:block/parent block)]
    (let [children (get-block-children-or-property-children block parent)
          right (some (fn [child] (when (> (compare (:block/order child) (:block/order block)) 0) child)) children)]
      (when (not= (:db/id right) (:db/id block))
        right))))

(defn get-left-sibling
  [block]
  (assert (or (de/entity? block) (nil? block)))
  (when-let [parent (:block/parent block)]
    (let [children (reverse (get-block-children-or-property-children block parent))
          left (some (fn [child] (when (< (compare (:block/order child) (:block/order block)) 0) child)) children)]
      (when (not= (:db/id left) (:db/id block))
        left))))

(defn get-down
  [block]
  (assert (or (de/entity? block) (nil? block)))
  (first (sort-by-order (:block/_parent block))))

(defn get-pages
  [db]
  (->> (d/q
        '[:find ?page-title
          :where
          [?page :block/name ?page-name]
          [(get-else $ ?page :block/title ?page-name) ?page-title]]
        db)
       (map first)
       (remove hidden?)))

(def get-first-page-by-name sqlite-common-db/get-first-page-by-name)

(def db-based-graph? entity-plus/db-based-graph?)

(defn page-exists?
  "Returns truthy value if page exists.
   For db graphs, returns all page db ids that given title and one of the given `tags`.
   For file graphs, returns page entity if it exists"
  [db page-name tags]
  (when page-name
    (if (db-based-graph? db)
      (let [tags' (if (coll? tags) (set tags) #{tags})]
        ;; Classes and properties are case sensitive and can be looked up
        ;; as such in case-sensitive contexts e.g. no Page
        (if (and (seq tags') (every? #{:logseq.class/Tag :logseq.class/Property} tags'))
          (seq
           (d/q
            '[:find [?p ...]
              :in $ ?name [?tag-ident ...]
              :where
              [?p :block/title ?name]
              [?p :block/tags ?tag]
              [?tag :db/ident ?tag-ident]]
            db
            page-name
            tags'))
          ;; TODO: Decouple db graphs from file specific :block/name
          (seq
           (d/q
            '[:find [?p ...]
              :in $ ?name [?tag-ident ...]
              :where
              [?p :block/name ?name]
              [?p :block/tags ?tag]
              [?tag :db/ident ?tag-ident]]
            db
            (common-util/page-name-sanity-lc page-name)
            tags'))))
      (d/entity db [:block/name (common-util/page-name-sanity-lc page-name)]))))

(defn get-page
  "Get a page given its unsanitized name"
  [db page-name-or-uuid]
  (when db
    (if-let [id (if (uuid? page-name-or-uuid) page-name-or-uuid
                    (parse-uuid page-name-or-uuid))]
      (d/entity db [:block/uuid id])
      (d/entity db (get-first-page-by-name db (name page-name-or-uuid))))))

(defn get-case-page
  "Case sensitive version of get-page. For use with DB graphs"
  [db page-name-or-uuid]
  (when db
    (if-let [id (if (uuid? page-name-or-uuid) page-name-or-uuid
                    (parse-uuid page-name-or-uuid))]
      (d/entity db [:block/uuid id])
      (d/entity db (sqlite-common-db/get-first-page-by-title db page-name-or-uuid)))))

(defn page-empty?
  "Whether a page is empty. Does it has a non-page block?
  `page-id` could be either a string or a db/id."
  [db page-id]
  (let [page-id (if (string? page-id)
                  (get-first-page-by-name db page-id)
                  page-id)
        page (d/entity db page-id)]
    (empty? (:block/_parent page))))

(defn get-first-child
  [db id]
  (first (sort-by-order (:block/_parent (d/entity db id)))))

(defn get-orphaned-pages
  [db {:keys [pages empty-ref-f built-in-pages-names]
       :or {empty-ref-f (fn [page] (zero? (count (:block/_refs page))))
            built-in-pages-names #{}}}]
  (let [pages (->> (or pages (get-pages db))
                   (remove nil?))
        built-in-pages (set (map string/lower-case built-in-pages-names))
        orphaned-pages (->>
                        (map
                         (fn [page]
                           (when-let [page (get-page db page)]
                             (let [name' (:block/name page)]
                               (and
                                (empty-ref-f page)
                                (or
                                 (page-empty? db (:db/id page))
                                 (let [first-child (get-first-child db (:db/id page))
                                       children (:block/_page page)]
                                   (and
                                    first-child
                                    (= 1 (count children))
                                    (contains? #{"" "-" "*"} (string/trim (:block/title first-child))))))
                                (not (contains? built-in-pages name'))
                                (not (whiteboard? page))
                                (not (:block/_namespace page))
                                (not (property? page))
                                 ;; a/b/c might be deleted but a/b/c/d still exists (for backward compatibility)
                                (not (and (string/includes? name' "/")
                                          (not (journal? page))))
                                (not (:block/properties page))
                                page))))
                         pages)
                        (remove false?)
                        (remove nil?)
                        (remove hidden?))]
    orphaned-pages))

(defn has-children?
  [db block-id]
  (some? (:block/_parent (d/entity db [:block/uuid block-id]))))

(defn- collapsed-and-has-children?
  [db block]
  (and (:block/collapsed? block) (has-children? db (:block/uuid block))))

(defn get-block-last-direct-child-id
  "Notice: if `not-collapsed?` is true, will skip searching for any collapsed block."
  ([db db-id]
   (get-block-last-direct-child-id db db-id false))
  ([db db-id not-collapsed?]
   (when-let [block (d/entity db db-id)]
     (when (if not-collapsed?
             (not (collapsed-and-has-children? db block))
             true)
       (let [children (sort-by :block/order (:block/_parent block))]
         (:db/id (last children)))))))

(defn get-children
  "Doesn't include nested children."
  ([block-entity]
   (get-children nil block-entity))
  ([db block-entity-or-eid]
   (when-let [parent (cond
                       (number? block-entity-or-eid)
                       (d/entity db block-entity-or-eid)
                       (uuid? block-entity-or-eid)
                       (d/entity db [:block/uuid block-entity-or-eid])
                       :else
                       block-entity-or-eid)]
     (sort-by-order (:block/_parent parent)))))

(defn get-block-parents
  [db block-id & {:keys [depth] :or {depth 100}}]
  (loop [block-id block-id
         parents' (list)
         d 1]
    (if (> d depth)
      parents'
      (if-let [parent (:block/parent (d/entity db [:block/uuid block-id]))]
        (recur (:block/uuid parent) (conj parents' parent) (inc d))
        parents'))))

(def get-block-children-ids sqlite-common-db/get-block-children-ids)
(def get-block-children sqlite-common-db/get-block-children)

(defn- get-sorted-page-block-ids
  [db page-id]
  (let [root (d/entity db page-id)]
    (loop [result []
           children (sort-by-order (:block/_parent root))]
      (if (seq children)
        (let [child (first children)]
          (recur (conj result (:db/id child))
                 (concat
                  (sort-by-order (:block/_parent child))
                  (rest children))))
        result))))

(defn sort-page-random-blocks
  "Blocks could be non consecutive."
  [db blocks]
  (assert (every? #(= (:block/page %) (:block/page (first blocks))) blocks) "Blocks must to be in a same page.")
  (let [page-id (:db/id (:block/page (first blocks)))
        ;; TODO: there's no need to sort all the blocks
        sorted-ids (get-sorted-page-block-ids db page-id)
        blocks-map (zipmap (map :db/id blocks) blocks)]
    (keep blocks-map sorted-ids)))

(defn last-child-block?
  "The child block could be collapsed."
  [db parent-id child-id]
  (when-let [child (d/entity db child-id)]
    (cond
      (= parent-id child-id)
      true

      (get-right-sibling child)
      false

      :else
      (last-child-block? db parent-id (:db/id (:block/parent child))))))

(defn- consecutive-block?
  [db block-1 block-2]
  (let [aux-fn (fn [block-1 block-2]
                 (and (= (:block/page block-1) (:block/page block-2))
                      (or
                       ;; sibling or child
                       (= (:db/id (get-left-sibling block-2)) (:db/id block-1))
                       (when-let [prev-sibling (get-left-sibling (d/entity db (:db/id block-2)))]
                         (last-child-block? db (:db/id prev-sibling) (:db/id block-1))))))]
    (or (aux-fn block-1 block-2) (aux-fn block-2 block-1))))

(defn get-non-consecutive-blocks
  [db blocks]
  (vec
   (keep-indexed
    (fn [i _block]
      (when (< (inc i) (count blocks))
        (when-not (consecutive-block? db (nth blocks i) (nth blocks (inc i)))
          (nth blocks i))))
    blocks)))

(defn new-block-id
  []
  (common-uuid/gen-uuid))

(defn get-classes-with-property
  "Get classes which have given property as a class property"
  [db property-id]
  (:logseq.property.class/_properties (d/entity db property-id)))

(defn get-alias-source-page
  "return the source page (page-name) of an alias"
  [db alias-id]
  (when alias-id
      ;; may be a case that a user added same alias into multiple pages.
      ;; only return the first result for idiot-proof
    (first (:block/_alias (d/entity db alias-id)))))

(defn get-block-alias
  [db eid]
  (->>
   (d/q
    '[:find [?e ...]
      :in $ ?eid %
      :where
      (alias ?eid ?e)]
    db
    eid
    (:alias rules/rules))
   distinct))

(defn get-block-refs
  [db id]
  (let [entity (d/entity db id)
        alias (->> (get-block-alias db id)
                   (cons id)
                   distinct)
        refs (->> (mapcat (fn [id]
                            (->> (:block/_refs (d/entity db id))
                                 (remove (fn [ref]
                                           ;; remove refs that have the block as either tag or property
                                           (or (and
                                                (class? entity)
                                                (d/datom db :eavt (:db/id ref) :block/tags (:db/id entity)))
                                               (and
                                                (property? entity)
                                                (d/datom db :eavt (:db/id ref) (:db/ident entity))))))))
                          alias)
                  distinct)]
    (when (seq refs)
      (d/pull-many db '[*] (map :db/id refs)))))

(defn get-block-refs-count
  [db id]
  (some-> (d/entity db id)
          :block/_refs
          count))

(defn hidden-or-internal-tag?
  [e]
  (or (entity-util/hidden? e) (db-class/internal-tags (:db/ident e))))

(defn get-all-pages
  [db]
  (->>
   (d/datoms db :avet :block/name)
   (keep (fn [d]
           (let [e (d/entity db (:e d))]
             (when-not (hidden-or-internal-tag? e)
               e))))))

(def built-in? entity-util/built-in?)

(defn built-in-class-property?
  "Whether property a built-in property for the specific class"
  [class-entity property-entity]
  (and (built-in? class-entity)
       (class? class-entity)
       (built-in? property-entity)
       (contains? (set (get-in (db-class/built-in-classes (:db/ident class-entity)) [:schema :properties]))
                  (:db/ident property-entity))))

(defn private-built-in-page?
  "Private built-in pages should not be navigable or searchable by users. Later it
   could be useful to use this for the All Pages view"
  [page]
  (cond (property? page)
        (not (public-built-in-property? page))
        (or (class? page) (internal-page? page))
        false
        ;; Default to true for closed value and future internal types.
        ;; Other types like whiteboard are not considered because they aren't built-in
        :else
        true))

(def write-transit-str sqlite-util/write-transit-str)
(def read-transit-str sqlite-util/read-transit-str)

(defn build-favorite-tx
  "Builds tx for a favorite block in favorite page"
  [favorite-uuid]
  {:block/link [:block/uuid favorite-uuid]
   :block/title ""})

(defn get-key-value
  [db key-ident]
  (:kv/value (d/entity db key-ident)))

(def kv sqlite-util/kv)

;; TODO: why not generate a UUID for all local graphs?
;; And prefer this local graph UUID when picking an ID for new rtc graph?
(defn get-graph-rtc-uuid
  [db]
  (when db (get-key-value db :logseq.kv/graph-uuid)))

(defn get-graph-schema-version
  [db]
  (when db (get-key-value db :logseq.kv/schema-version)))

(defn get-graph-remote-schema-version
  [db]
  (when db (get-key-value db :logseq.kv/remote-schema-version)))

(defn get-all-properties
  [db]
  (->> (d/datoms db :avet :block/tags :logseq.class/Property)
       (map (fn [d]
              (d/entity db (:e d))))))

(defn get-page-parents
  [node & {:keys [node-class?]}]
  (when-let [parent (:logseq.property/parent node)]
    (loop [current-parent parent
           parents' []]
      (if (and
           current-parent
           (if node-class? (class? current-parent) true)
           (not (contains? parents' current-parent)))
        (recur (:logseq.property/parent current-parent)
               (conj parents' current-parent))
        (vec (reverse parents'))))))

(defn get-title-with-parents
  [entity]
  (if (or (entity-util/class? entity) (entity-util/internal-page? entity))
    (let [parents' (->> (get-page-parents entity)
                        (remove (fn [e] (= :logseq.class/Root (:db/ident e))))
                        vec)]
      (string/join
       ns-util/parent-char
       (map :block/title (conj (vec parents') entity))))
    (:block/title entity)))

(defn get-classes-parents
  [tags]
  (let [tags' (filter class? tags)
        result (mapcat #(get-page-parents % {:node-class? true}) tags')]
    (set result)))

(defn class-instance?
  "Whether `object` is an instance of `class`"
  [class object]
  (let [tags (:block/tags object)
        tags-ids (set (map :db/id tags))]
    (or
     (contains? tags-ids (:db/id class))
     (let [class-parent-ids (set (map :db/id (get-classes-parents tags)))]
       (contains? (set/union class-parent-ids tags-ids) (:db/id class))))))

(defn inline-tag?
  [block-raw-title tag]
  (assert (string? block-raw-title) "block-raw-title should be a string")
  (string/includes? block-raw-title (str "#" (page-ref/->page-ref (:block/uuid tag)))))

(defonce node-display-type-classes
  #{:logseq.class/Code-block :logseq.class/Math-block :logseq.class/Quote-block})

(defn get-class-ident-by-display-type
  [display-type]
  (case display-type
    :code :logseq.class/Code-block
    :math :logseq.class/Math-block
    :quote :logseq.class/Quote-block
    nil))

(defn get-display-type-by-class-ident
  [class-ident]
  (case class-ident
    :logseq.class/Code-block :code
    :logseq.class/Math-block :math
    :logseq.class/Quote-block :quote
    nil))

(def get-recent-updated-pages sqlite-common-db/get-recent-updated-pages)
