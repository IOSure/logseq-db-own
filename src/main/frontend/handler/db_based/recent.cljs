(ns frontend.handler.db-based.recent
  "Fns related to recent pages feature"
  (:require [frontend.db :as db]
            [logseq.graph-parser.util :as gp-util]))

(defn add-page-to-recent!
  [repo page click-from-recent?]
  (when-let [page-uuid (if (uuid? page)
                         page
                         (:block/uuid (db/entity [:block/name (gp-util/page-name-sanity-lc page)])))]
    (let [pages (or (db/get-key-value repo :recent/pages)
                    '())]
      (when (or (and click-from-recent? (not ((set pages) page-uuid)))
                (not click-from-recent?))
        (let [new-pages (take 15 (distinct (cons page-uuid pages)))]
          (db/set-key-value repo :recent/pages new-pages))))))

(defn get-recent-pages
  []
  (->> (db/get-key-value :recent/pages)
       (map #(:block/original-name (db/entity [:block/uuid %])))))
