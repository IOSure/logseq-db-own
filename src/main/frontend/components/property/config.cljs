(ns frontend.components.property.config
  (:require [clojure.string :as string]
            [frontend.components.dnd :as dnd]
            [frontend.components.icon :as icon-component]
            [frontend.config :as config]
            [frontend.handler.common.developer :as dev-common-handler]
            [frontend.handler.db-based.property :as db-property-handler]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.handler.property :as property-handler]
            [frontend.handler.route :as route-handler]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.db :as ldb]
            [logseq.db.frontend.order :as db-order]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.property.type :as db-property-type]
            [logseq.outliner.core :as outliner-core]
            [logseq.shui.ui :as shui]
            [logseq.shui.popup.core :as shui-popup]
            [promesa.core :as p]
            [goog.dom :as gdom]
            [rum.core :as rum]
            [frontend.db-mixins :as db-mixins]))

(defn- re-init-commands!
  "Update commands after task status and priority's closed values has been changed"
  [property]
  (when (contains? #{:logseq.task/status :logseq.task/priority} (:db/ident property))
    (state/pub-event! [:init/commands])))

(defn- <upsert-closed-value!
  "Create new closed value and returns its block UUID."
  [property item]
  (p/do!
    (db-property-handler/upsert-closed-value! (:db/ident property) item)
    (re-init-commands! property)))

(defn- loop-focusable-elements!
  ([^js cnt] (loop-focusable-elements! cnt
               ".ui__button:not([disabled]), .ui__input, .ui__textarea"))
  ([^js cnt selectors]
   (when-let [els (some-> cnt (.querySelectorAll selectors) (seq))]
     (let [active js/document.activeElement
           current-idx (.indexOf els active)
           total-len (count els)
           to-idx (cond
                    (or (= -1 current-idx)
                      (= total-len (inc current-idx)))
                    0
                    :else
                    (inc current-idx))]
       (some-> els (nth to-idx) (.focus))))))

(defn- set-property-description!
  [property description]
  (if-let [ent (:logseq.property/description property)]
    (db/transact! (state/get-current-repo)
      [(outliner-core/block-with-updated-at
         {:db/id (:db/id ent) :block/title description})]
      {:outliner-op :save-block})
    (when-not (string/blank? description)
      (db-property-handler/set-block-property!
        (:db/id property)
        :logseq.property/description description))))

(rum/defc name-edit-pane
  [property {:keys [set-sub-open! disabled?]}]
  (let [*form-data (rum/use-ref {:icon (:logseq.property/icon property)
                                 :title (or (:block/title property) "")
                                 :description (or (db-property/property-value-content (:logseq.property/description property)) "")})
        [form-data, set-form-data!] (rum/use-state (rum/deref *form-data))
        [saving?, set-saving!] (rum/use-state false)
        *el (rum/use-ref nil)
        *input-ref (rum/use-ref nil)
        title (util/trim-safe (:title form-data))
        description (util/trim-safe (:description form-data))]

    (rum/use-effect!
      (fn []
        (js/setTimeout #(some-> (rum/deref *el) (.focus)) 32))
      [])

    [:div.ls-property-name-edit-pane.outline-none
     {:on-key-down (fn [^js e] (when (= "Tab" (.-key e))
                                 (loop-focusable-elements! (rum/deref *el))))
      :tab-index -1
      :ref *el}
     [:div.flex.items-center.input-wrap
      (icon-component/icon-picker (:icon form-data)
        {:on-chosen (fn [_e icon] (set-form-data! (assoc form-data :icon icon)))
         :popup-opts {:align "start"}
         :empty-label "?"})
      (shui/input {:ref *input-ref :size "sm" :default-value title :placeholder "name"
                   :disabled disabled? :on-change (fn [^js e] (set-form-data! (assoc form-data :title (util/trim-safe (util/evalue e)))))})]
     [:div.pt-2 (shui/textarea {:placeholder "description" :default-value description
                                :disabled disabled? :on-change (fn [^js e] (set-form-data! (assoc form-data :description (util/trim-safe (util/evalue e)))))})]

     (let [dirty? (not= (rum/deref *form-data) form-data)]
       [:div.pt-2.flex.justify-end
        (shui/button {:size "sm" :disabled (or saving? (not dirty?))
                      :variant (if dirty? :default :secondary)
                      :on-click (fn []
                                  (when (string/blank? title)
                                    (some-> (rum/deref *input-ref) (.focus))
                                    (throw (js/Error. "property name is empty")))

                                  (set-saving! true)
                                  (-> [(db-property-handler/upsert-property!
                                         (:db/ident property)
                                         (:block/schema property)
                                         {:property-name title
                                          :properties {:logseq.property/icon (:icon form-data)}})
                                       (when (not= description (:description (rum/deref *form-data)))
                                         (set-property-description! property description))]
                                    (p/all)
                                    (p/then #(set-sub-open! false))
                                    (p/catch #(shui/toast! (str %) :error))
                                    (p/finally #(set-saving! false))))}
          "Save")])]))

(rum/defc base-edit-form
  [own-property block]
  (let [create? (:create? block)
        uuid (:block/uuid block)
        *form-data (rum/use-ref
                     {:value (or (:block/title block) "")
                      :icon (:logseq.property/icon block)
                      :description ""})
        [form-data, set-form-data!] (rum/use-state (rum/deref *form-data))
        *input-ref (rum/use-ref nil)]

    (rum/use-effect!
      (fn []
        (when create?
          (js/setTimeout #(some-> (rum/deref *input-ref) (.focus)) 60)))
      [])

    [:div.ls-base-edit-form
     [:div.flex.items-center.input-wrap
      (icon-component/icon-picker
        (:icon form-data)
        {:on-chosen (fn [_e icon] (set-form-data! (assoc form-data :icon icon)))
         :empty-label "?"
         :popup-opts {:align "start"}})

      (shui/input {:ref *input-ref :size "sm"
                   :default-value (:value form-data)
                   :on-change (fn [^js e] (set-form-data! (assoc form-data :value (util/trim-safe (util/evalue e)))))
                   :placeholder "title"})]
     [:div.pt-2 (shui/textarea
                  {:placeholder "description" :default-value (:description form-data)
                   :on-change (fn [^js e] (set-form-data! (assoc form-data :description (util/trim-safe (util/evalue e)))))})]
     [:div.pt-2.flex.justify-end
      (let [dirty? (not= (rum/deref *form-data) form-data)]
        (shui/button {:size "sm"
                      :disabled (not dirty?)
                      :on-click (fn []
                                  (-> (<upsert-closed-value! own-property
                                        (cond-> form-data uuid (assoc :id uuid)))
                                    (p/then #(shui/popup-hide!))
                                    (p/catch #(shui/toast! (str %) :error))))
                      :variant (if dirty? :default :secondary)}
          "Save"))]]))

(defn restore-root-highlight-item!
  [id]
  (js/setTimeout
    #(some-> (gdom/getElement id) (.focus)) 32))

(rum/defc dropdown-editor-menuitem
  [{:keys [id icon title desc submenu-content item-props sub-content-props disabled? toggle-checked? on-toggle-checked-change]}]

  (let [[sub-open? set-sub-open!] (rum/use-state false)
        toggle? (boolean? toggle-checked?)
        id1 (str (or id icon (random-uuid)))
        id2 (str "d2-" id1)
        or-close-menu-sub! (fn []
                             (when (and (not (shui-popup/get-popup :ls-icon-picker))
                                     (not (shui-popup/get-popup :ls-base-edit-form)))
                               (set-sub-open! false)
                               (restore-root-highlight-item! id1)))
        wrap-menuitem (if submenu-content
                        #(shui/dropdown-menu-sub
                           {:open sub-open?
                            :on-open-change (fn [v] (if v (set-sub-open! true) (or-close-menu-sub!)))}
                           (shui/dropdown-menu-sub-trigger (merge {:id id1} item-props) %)
                           (shui/dropdown-menu-portal
                             (shui/dropdown-menu-sub-content
                               (merge {:hideWhenDetached true
                                       :onEscapeKeyDown or-close-menu-sub!} sub-content-props)
                               (if (fn? submenu-content)
                                 (submenu-content {:set-sub-open! set-sub-open! :id id1}) submenu-content))))
                        #(shui/dropdown-menu-item
                           (merge {:on-select (fn []
                                                (when toggle?
                                                  (some-> (gdom/getElement id2) (.click))))
                                   :id id1}
                             item-props) %))]
    (wrap-menuitem
      [:div.inner-wrap
       {:class (util/classnames [{:disabled disabled?}])}
       [:strong
        (some-> icon (name) (shui/tabler-icon))
        [:span title]]
       (if (fn? desc) (desc)
         (if (boolean? toggle-checked?)
           [:span.scale-90.flex.items-center
            (shui/switch {:id id2 :size "sm" :checked toggle-checked?
                          :disabled disabled? :on-click #(util/stop-propagation %)
                          :on-checked-change (or on-toggle-checked-change identity)})]
           [:label [:span desc]
            (when disabled? (shui/tabler-icon "forbid-2" {:size 15}))]))])))

(rum/defc choice-item-content
  [property block]
  (let [delete-choice! (fn []
                         (p/do!
                           (db-property-handler/delete-closed-value! (:db/id property) (:db/id block))
                           (re-init-commands! property)))
        update-icon! (fn [icon]
                       (property-handler/set-block-property!
                         (state/get-current-repo) (:block/uuid block) :logseq.property/icon
                         (select-keys icon [:id :type :color])))
        icon (:logseq.property/icon block)
        value (db-property/closed-value-content block)]

    [:li
     (shui/tabler-icon "grip-vertical" {:size 14})
     (shui/button {:size "sm" :variant :outline}
       (icon-component/icon-picker icon {:on-chosen (fn [_e icon] (update-icon! icon))
                                         :popup-opts {:align "start"}
                                         :empty-label "?"}))
     [:strong {:on-click (fn [^js e]
                           (shui/popup-show! (.-target e)
                             (fn [] (base-edit-form property block))
                             {:id :ls-base-edit-form
                              :align "start"}))}
      value]
     [:a.del {:on-click delete-choice!
              :title "Delete this choice"}
      (shui/tabler-icon "x" {:size 16})]]))

(rum/defc add-existing-values
  [property values {:keys [toggle-fn]}]
  [:div.flex.flex-col.gap-1.w-64.p-4.overflow-y-auto
   {:class "max-h-[50dvh]"}
   [:div "Existing values:"]
   [:ol
    (for [value values]
      [:li (if (uuid? value)
             (let [result (db/entity [:block/uuid value])]
               (db-property/closed-value-content result))
             (str value))])]
   (shui/button
    {:on-click (fn []
                 (p/let [_ (db-property-handler/add-existing-values-to-closed-values! (:db/id property) values)]
                   (toggle-fn)))}
    "Add choices")])

(rum/defc choices-sub-pane < rum/reactive db-mixins/query
  [property]
  (let [values (:property/closed-values property)
        choices (doall
                 (keep (fn [value]
                         (when-let [block (db/sub-block (:db/id value))]
                           (let [id (:block/uuid block)]
                             {:id (str id)
                              :value id
                              :content (choice-item-content property block)})))
                       values))]
    [:div.ls-property-dropdown-editor.ls-property-choices-sub-pane
     (when (seq choices)
       [:ul.choices-list
        (dnd/items choices
                   {:sort-by-inner-element? false
                    :on-drag-end (fn [_ {:keys [active-id over-id direction]}]
                                   (let [move-down? (= direction :down)
                                         over (db/entity [:block/uuid (uuid over-id)])
                                         active (db/entity [:block/uuid (uuid active-id)])
                                         over-order (:block/order over)
                                         new-order (if move-down?
                                                     (let [next-order (db-order/get-next-order (db/get-db) property (:db/id over))]
                                                       (db-order/gen-key over-order next-order))
                                                     (let [prev-order (db-order/get-prev-order (db/get-db) property (:db/id over))]
                                                       (db-order/gen-key prev-order over-order)))]

                                     (db/transact! (state/get-current-repo)
                                                   [{:db/id (:db/id active)
                                                     :block/order new-order}
                                                    (outliner-core/block-with-updated-at
                                                     {:db/id (:db/id property)})]
                                                   {:outliner-op :save-block})))})])

     ;; add choice
     (dropdown-editor-menuitem
      {:icon :plus :title "Add choice"
       :item-props {:on-click
                    (fn [^js e]
                      (p/let [values (db-async/<get-block-property-values (state/get-current-repo) (:db/ident property))
                              existing-values (seq (:property/closed-values property))
                              values (if (seq existing-values)
                                       (let [existing-ids (set (map :db/id existing-values))]
                                         (remove (fn [id] (existing-ids id)) values))
                                       values)]
                        (shui/popup-show! (.-target e)
                                          (fn [{:keys [id]}]
                                            (let [opts {:toggle-fn (fn [] (shui/popup-hide! id))}
                                                  values' (->> (if (contains? db-property-type/ref-property-types (get-in property [:block/schema :type]))
                                                                 (map #(:block/uuid (db/entity %)) values)
                                                                 values)
                                                               (remove string/blank?)
                                                               distinct)]
                                              (if (seq values')
                                                (add-existing-values property values' opts)
                                                (base-edit-form property {:create? true}))))
                                          {:id :ls-base-edit-form
                                           :align "start"})))}})]))

(def position-labels
  {:properties {:icon :layout-distribute-horizontal :title "Block properties"}
   :block-left {:icon :layout-align-right :title "Beginning of the block"}
   :block-right {:icon :layout-align-left :title "End of the block"}
   :block-below {:icon :layout-align-top :title "Below of the block"}})

(rum/defc ui-position-sub-pane
  [property {:keys [id set-sub-open! _position]}]
  (let [handle-select! (fn [^js e]
                         (when-let [v (some-> (.-target e) (.-dataset) (.-value))]
                           (db-property-handler/upsert-property!
                             (:db/ident property)
                             (assoc (:block/schema property) :position (keyword v))
                             {:property-name (:block/title property)})
                           (set-sub-open! false)
                           (restore-root-highlight-item! id)))
        item-props {:on-select handle-select!}]
    [:div.ls-property-dropdown-editor.ls-property-ui-position-sub-pane
     (for [[k v] position-labels]
       (dropdown-editor-menuitem (assoc v :item-props (assoc item-props :data-value k))))]))

(defn- property-type-label
  [property-type]
  (case property-type
    :default
    "Text"
    ((comp string/capitalize name) property-type)))

(defn- handle-delete-property!
  [block property & {:keys [class? class-schema?]}]
  (let [class? (or class? (ldb/class? block))
        remove! #(let [repo (state/get-current-repo)]
                   (if (and class? class-schema?)
                     (db-property-handler/class-remove-property! (:db/id block) (:db/id property))
                     (property-handler/remove-block-property! repo (:block/uuid block) (:db/ident property))))]
    (if (and class? class-schema?)
      (-> (shui/dialog-confirm!
            ;; Only ask for confirmation on class schema properties
            [:p (str "Are you sure you want to delete this property?")])
        (p/then remove!))
      (remove!))))

(rum/defc dropdown-editor-impl
  "property: block entity"
  [property owner-block opts]
  (let [title (:block/title property)
        property-schema (:block/schema property)
        property-type (get property-schema :type)
        property-type-label' (some-> property-type (property-type-label))
        enable-closed-values? (contains? db-property-type/closed-value-property-types
                                (or property-type :default))
        icon (:logseq.property/icon property)
        icon (when icon [:span.float-left.w-4.h-4.overflow-hidden.leading-4.relative
                         (icon-component/icon icon {:size 15})])
        built-in? (ldb/built-in? property)
        disabled? (or built-in? config/publishing?)]
    [:<>
     (dropdown-editor-menuitem {:icon :edit :title "Property name" :desc [:span.flex.items-center.gap-1 icon title]
                                :submenu-content (fn [ops] (name-edit-pane property (assoc ops :disabled? disabled?)))})
     (dropdown-editor-menuitem {:icon :hash :title "Property type" :desc (str property-type-label') :disabled? true})

     (when enable-closed-values? (empty? (:property/schema.classes property))
       (let [values (:property/closed-values property)]
         (dropdown-editor-menuitem {:icon :list :title "Available choices"
                                    :desc (when (seq values) (str (count values) " choices"))
                                    :submenu-content (fn [] (choices-sub-pane property))})))

     (let [many? (db-property/many? property)]
       (dropdown-editor-menuitem {:icon :checks :title "Multiple values"
                                  :toggle-checked? many? :disabled? disabled?
                                  :on-toggle-checked-change #(db-property-handler/upsert-property! (:db/ident property)
                                                               (assoc property-schema :cardinality (if many? :one :many)) {})}))

     (shui/dropdown-menu-separator)
     (let [position (:position property-schema)]
       (dropdown-editor-menuitem {:icon :float-left :title "UI position" :desc (some->> position (get position-labels) (:title))
                                  :item-props {:class "ui__position-trigger-item"}
                                  :submenu-content (fn [ops] (ui-position-sub-pane property (assoc ops :position position)))}))

     (dropdown-editor-menuitem {:icon :eye-off :title "Hide by default" :toggle-checked? (boolean (:hide? property-schema))
                                :on-toggle-checked-change #(db-property-handler/upsert-property! (:db/ident property)
                                                             (assoc property-schema :hide? %) {})})

     (shui/dropdown-menu-separator)
     (dropdown-editor-menuitem
       {:icon :share-3 :title "Go to the node" :desc ""
        :item-props {:class "opacity-90 focus:opacity-100"
                     :on-select (fn []
                                  (shui/popup-hide-all!)
                                  (route-handler/redirect-to-page! (:block/uuid property)))}})
     (dropdown-editor-menuitem
       {:id :remove-property :icon :square-x :title "Remove property" :desc "" :disabled? false
        :item-props {:class "opacity-60 focus:opacity-100 focus:!text-red-rx-09"
                     :on-select (fn [^js e]
                                  (util/stop e)
                                  (-> (shui/dialog-confirm!
                                        "Are you sure you want to delete property from this node?"
                                        {:id :delete-property-from-node
                                         :data-reminder :ok})
                                    (p/then (fn []
                                              (handle-delete-property! owner-block property {:class-schema? false})
                                              (shui/popup-hide-all!)))
                                    (p/catch (fn [] (restore-root-highlight-item! :remove-property)))))}})
     (when (:debug? opts)
       [:<>
        (shui/dropdown-menu-separator)
        (dropdown-editor-menuitem
          {:icon :bug :title (str (:db/ident property)) :desc "" :disabled? false
           :item-props {:class "opacity-60 focus:opacity-100 focus:!text-red-rx-08"
                        :on-select (fn []
                                     (dev-common-handler/show-entity-data (:db/id property))
                                     (shui/popup-hide!))}})])]))

(rum/defc dropdown-editor < rum/reactive db-mixins/query
  [property* owner-block opts]
  (let [property (db/sub-block (:db/id property*))]
    (dropdown-editor-impl property owner-block opts)))
