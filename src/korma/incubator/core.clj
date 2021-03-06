(ns korma.incubator.core
  "Core querying and entity functions"
  (:require [korma.sql.engine :as eng]
            [korma.sql.fns :as sfns]
            [korma.sql.utils :as utils]
            [clojure.set :as set]
            [clojure.string :as string]
            [korma.db :as db]
            [korma.config :as conf])
  (:use [korma.sql.engine :only [bind-query bind-params]]))

(def ^{:dynamic true} *exec-mode* false)
(declare get-rel)

;;*****************************************************
;; Query types
;;*****************************************************

(defn- check-ent [ent]
  (when-not (or (string? ent)
                (map? ent))
    (throw (Exception. (str "Invalid entity provided for the query: " ent)))))

(defn empty-query [ent]
  (let [ent (if (keyword? ent)
              (name ent)
              ent)
        [ent table alias db opts] (if (string? ent)
                                    [{:table ent} ent nil nil nil]
                                    [ent (:table ent) (:alias ent) 
                                     (:db ent) (get-in ent [:db :options])])]
    {:ent ent
     :table table
     :db db
     :options opts
     :alias alias}))

(defn select* 
  "Create an empty select query. Ent can either be an entity defined by defentity,
  or a string of the table name"
  [ent]
  (if (:type ent)
    ent
    (let [q (empty-query ent)]
      (merge q {:type :select
                :fields [::*]
                :from [(:ent q)]
                :modifiers []
                :joins []
                :where []
                :order []
                :aliases #{}
                :group []
                :results :results}))))

(defn update* 
  "Create an empty update query. Ent can either be an entity defined by defentity,
  or a string of the table name."
  [ent]
  (if (:type ent)
    ent
    (let [q (empty-query ent)]
      (merge q {:type :update
                :fields {}
                :where []
                :results :keys}))))

(defn delete* 
  "Create an empty delete query. Ent can either be an entity defined by defentity,
  or a string of the table name"
  [ent]
  (if (:type ent)
    ent
    (let [q (empty-query ent)]
      (merge q {:type :delete
                :where []
                :results :keys}))))

(defn insert* 
  "Create an empty insert query. Ent can either be an entity defined by defentity,
  or a string of the table name"
  [ent]
  (if (:type ent)
    ent
    (let [q (empty-query ent)]
      (merge q {:type :insert
                :values []
                :results :keys}))))

;;*****************************************************
;; Query macros
;;*****************************************************

(defmacro select 
  "Creates a select query, applies any modifying functions in the body and then
  executes it. `ent` is either a string or an entity created by defentity.
  
  ex: (select user 
        (fields :name :email)
        (where {:id 2}))"
  [ent & body]
  `(let [query# (-> (select* ~ent)
                    ~@body)]
     (exec query#)))

(defmacro update 
  "Creates an update query, applies any modifying functions in the body and then
  executes it. `ent` is either a string or an entity created by defentity.
  
  ex: (update user 
        (set-fields {:name \"chris\"}) 
        (where {:id 4}))"
  [ent & body]
  `(let [query# (-> (update* ~ent)
                    ~@body)]
     (exec query#)))

(defmacro delete 
  "Creates a delete query, applies any modifying functions in the body and then
  executes it. `ent` is either a string or an entity created by defentity.
  
  ex: (delete user 
        (where {:id 7}))"
  [ent & body]
  `(let [query# (-> (delete* ~ent)
                    ~@body)]
     (exec query#)))

(defmacro insert 
  "Creates an insert query, applies any modifying functions in the body and then
  executes it. `ent` is either a string or an entity created by defentity. Inserts
  return the last inserted id.
  
  ex: (insert user 
        (values [{:name \"chris\"} {:name \"john\"}]))"
  [ent & body]
  `(let [query# (-> (insert* ~ent)
                    ~@body)]
     (exec query#)))

;;*****************************************************
;; Query parts
;;*****************************************************

(defn- add-aliases [query as]
  (update-in query [:aliases] set/union as))

(defn- update-fields [query fs]
  (let [[first-cur] (:fields query)]
    (if (= first-cur ::*)
      (assoc query :fields fs)
      (update-in query [:fields] concat fs))))

(defn fields
  "Set the fields to be selected in a query. Fields can either be a keyword
  or a vector of two keywords [field alias]:
  
  (fields query :name [:firstname :first])"
  [query & vs] 
  (let [aliases (set (map second (filter coll? vs)))]
    (-> query
        (add-aliases aliases)
        (update-fields vs))))

(defn set-fields
  "Set the fields and values for an update query."
  [query fields-map]
  (update-in query [:set-fields] merge fields-map))

(defn from
  "Add tables to the from clause."
  [query table]
  (update-in query [:from] conj table))

(defn where*
  "Add a where clause to the query. Clause can be either a map or a string, and
  will be AND'ed to the other clauses."
  [query clause]
  (update-in query [:where] conj clause))

(defmacro where
  "Add a where clause to the query, expressing the clause in clojure expressions
  with keywords used to reference fields.
  e.g. (where query (or (= :hits 1) (> :hits 5)))

  Available predicates: and, or, =, not=, <, >, <=, >=, in, like, not

  Where can also take a map at any point and will create a clause that compares keys
  to values. The value can be a vector with one of the above predicate functions 
  describing how the key is related to the value: (where query {:name [like \"chris\"})"
  [query form]
  `(let [q# ~query]
     (where* q# 
             (bind-query q#
                         (eng/pred-map
                          ~(eng/parse-where `~form))))))

(defn order
  "Add an ORDER BY clause to a select query. field should be a keyword of the field name, dir
  is ASC by default.
  
  (order query :created :asc)"
  [query field & [dir]]
  (update-in query [:order] conj [field (or dir :ASC)]))

(defn values
  "Add records to an insert clause. values can either be a vector of maps or a single
  map.
  
  (values query [{:name \"john\"} {:name \"ed\"}])"
  [query values]
  (update-in query [:values] concat (if (map? values)
                                      [values]
                                      values)))

(defn join* [query type table clause]
  (update-in query [:joins] conj [type table clause]))

(defn add-joins
  "Adds join clauses to a query for a relationship.  If the relationship uses a
   join table then two clauses will be added.  Otherwise, only one clause will
   be added."
  [query ent rel]
  (if-let [join-table (:join-table rel)]
    (-> query
        (join* :left join-table (sfns/pred-= (:lpk rel) @(:lfk rel)))
        (join* :left ent (sfns/pred-= @(:rfk rel) (:rpk rel))))
    (join* query :left ent (sfns/pred-= (:pk rel) (:fk rel)))))

(defmacro join 
  "Add a join clause to a select query, specifying the table name to join and the predicate
  to join on.
  
  (join query addresses)
  (join query addresses (= :addres.users_id :users.id))
  (join query :right addresses (= :address.users_id :users.id))"
  ([query ent]
     `(let [q# ~query
            e# ~ent
            rel# (get-rel (:ent q#) e#)]
        (add-joins q# e# rel#)))
  ([query table clause]
     `(join* ~query :left ~table (eng/pred-map ~(eng/parse-where clause))))
  ([query type table clause]
     `(join* ~query ~type ~table (eng/pred-map ~(eng/parse-where clause)))))

(defn post-query
  "Add a function representing a query that should be executed for each result in a select.
  This is done lazily over the result set."
  [query post]
  (update-in query [:post-queries] conj post))

(defn limit
  "Add a limit clause to a select query."
  [query v]
  (assoc query :limit v))

(defn offset
  "Add an offset clause to a select query."
  [query v]
  (assoc query :offset v))

(defn group
  "Add a group-by clause to a select query"
  [query & fields]
  (update-in query [:group] concat fields))

(defmacro aggregate
  "Use a SQL aggregator function, aliasing the results, and optionally grouping by
  a field:
  
  (select users 
    (aggregate (count :*) :cnt :status))
  
  Aggregates available: count, sum, avg, min, max, first, last"
  [query agg alias & [group-by]]
  `(let [q# ~query]
     (bind-query q#
                 (let [res# (fields q# [~(eng/parse-aggregate agg) ~alias])]
                   (if ~group-by
                     (group res# ~group-by)
                     res#)))))

;;*****************************************************
;; Other sql
;;*****************************************************

(defn sqlfn*
  "Call an arbitrary SQL function by providing the name of the function
  and its params"
  [fn-name & params]
  (apply eng/sql-func (name fn-name) params))

(defmacro sqlfn
  "Call an arbitrary SQL function by providing func as a symbol or keyword
  and its params"
  [func & params]
  `(sqlfn* (quote ~func) ~@params))

(defmacro subselect
  "Create a subselect clause to be used in queries. This works exactly like (select ...)
  execept it will wrap the query in ( .. ) and make sure it can be used in any current
  query:

  (select users
    (where {:id [in (subselect users2 (fields :id))]}))"
  [& parts]
  `(utils/sub-query (query-only (select ~@parts))))

(defn modifier
  "Add a modifer to the beginning of a query:

  (select orders
    (modifier \"DISTINCT\"))"
  [query & modifiers]
  (update-in query [:modifiers] conj (reduce str modifiers)))

(defn raw
  "Embed a raw string of SQL in a query. This is used when Korma doesn't
  provide some specific functionality you're looking for:

  (select users
    (fields (raw \"PERIOD(NOW(), NOW())\")))"
  [s]
  (utils/generated s))

;;*****************************************************
;; Query exec
;;*****************************************************

(defmacro sql-only
  "Wrap around a set of queries so that instead of executing, each will return a string of the SQL 
  that would be used."
  [& body]
  `(binding [*exec-mode* :sql]
     ~@body))

(defmacro dry-run
  "Wrap around a set of queries to print to the console all SQL that would 
  be run and return dummy values instead of executing them."
  [& body]
  `(binding [*exec-mode* :dry-run]
     ~@body))

(defmacro query-only
  "Wrap around a set of queries to force them to return their query objects."
  [& body]
  `(binding [*exec-mode* :query]
     ~@body))

(defn as-sql
  "Force a query to return a string of SQL when (exec) is called."
  [query]
  (bind-query query (:sql-str (eng/->sql query))))

(defn- apply-posts
  [query results]
  (if-let [posts (seq (:post-queries query))]
    (let [post-fn (apply comp posts)]
      (post-fn results))
    results))

(defn- apply-transforms
  [query results]
  (if (not= (:type query) :select)
    results
    (if-let [trans (seq (-> query :ent :transforms))]
      (let [trans-fn (apply comp trans)]
        (map trans-fn results))
      results)))

(defn- apply-prepares
  [query]
  (if-let [preps (seq (-> query :ent :prepares))]
    (let [preps (apply comp preps)]
      (condp = (:type query)
        :insert (let [values (:values query)]
                  (assoc query :values (map preps values)))
        :update (let [value (:set-fields query)]
                  (assoc query :set-fields (preps value)))
        query))
    query))

(defn- get-fkk
  "Gets the foreign key keyword from a query."
  [query]
  (let [rel (-> query :ent :rel)]
    (when-not (nil? rel)
      (let [rel (force rel)]
        (when-not (empty? rel)
          (-> rel first val deref :fkk))))))

(defn exec
  "Execute a query map and return the results."
  [query]
  (let [query (apply-prepares query)
        query (bind-query query (eng/->sql query))
        sql (:sql-str query)
        params (:params query)]
    (cond
     (:sql query) sql
     (= *exec-mode* :sql) sql
     (= *exec-mode* :query) query
     (= *exec-mode* :dry-run) (do
                                (println "dry run ::" sql "::" (vec params))
                                (let [pk  (-> query :ent :pk)
                                      fkk (get-fkk query)
                                      res (if (nil? fkk) {pk 1} {pk 1 fkk 1})
                                      results (apply-posts query [res])]
                                  (first results)
                                  results))
     :else (let [results (db/do-query query)]
             (apply-transforms query (apply-posts query results))))))

(defn exec-raw
  "Execute a raw SQL string, supplying whether results should be returned. `sql` can either be
  a string or a vector of the sql string and its params. You can also optionally
  provide the connection to execute against as the first parameter.

  (exec-raw [\"SELECT * FROM users WHERE age > ?\" [5]] :results)"
  [conn? & [sql with-results?]]
  (let [sql-vec (fn [v] (if (vector? v) v [v nil]))
        [conn? [sql-str params] with-results?] (if (or (string? conn?)
                                                       (vector? conn?))
                                                 [nil (sql-vec conn?) sql]
                                                 [conn? (sql-vec sql) with-results?])]
    (db/do-query {:db conn? :results with-results? :sql-str sql-str :params params})))

;;*****************************************************
;; Entities
;;*****************************************************

(defn create-entity
  "Create an entity representing a table in a database."
  [table]
  {:table table
   :name table
   :pk :id
   :db nil
   :transforms '()
   :prepares '()
   :fields []
   :rel {}})

(defn many-to-many-keys
  "Determines and returns the keys needed for a many-to-many relationship."
  [parent child {:keys [join-table lfk rfk]}]
  {:lpk (raw (eng/prefix parent (:pk parent)))
   :lfk (delay (raw (eng/prefix {:table (name join-table)} @lfk)))
   :rfk (delay (raw (eng/prefix {:table (name join-table)} @rfk)))
   :rpk (raw (eng/prefix child (:pk child)))
   :join-table join-table})

(defn get-belongs-to-keys
  "Determines and returns the keys needed for belongs-to relationshiops."
  [parent child]
  (let [pkk (:pk parent)
        fkk (keyword (str (:table parent) "_id"))]
    {:pk  (raw (eng/prefix parent pkk))
     :fk  (raw (eng/prefix child fkk))
     :fkk fkk}))

(defn get-db-keys
  "Determines and returns the keys needed for has-one and has-many
   relationshiops."
  [parent child]
  (let [pkk (:pk parent)
        fkk (keyword (str (:table parent) "_id"))]
    {:pk  (raw (eng/prefix parent pkk))
     :fk  (raw (eng/prefix child fkk))}))

(defn db-keys-and-foreign-ent
  "Determines and returns the database keys and foreign entity for a
   relationship."
  [type ent sub-ent opts]
  (condp = type
    :many-to-many [(many-to-many-keys ent sub-ent opts) sub-ent]
    :has-one      [(get-db-keys ent sub-ent) sub-ent]
    :belongs-to   [(get-belongs-to-keys sub-ent ent) ent]
    :has-many     [(get-db-keys ent sub-ent) sub-ent]))

(defn create-rel
  [ent sub-ent type opts]
  (let [[db-keys foreign-ent] (db-keys-and-foreign-ent type ent sub-ent opts)
        opts (when (:fk opts)
               {:fk (raw (eng/prefix foreign-ent (:fk opts)))})]
    (merge {:table (:table sub-ent)
            :alias (:alias sub-ent)
            :rel-type type}
           db-keys
           opts)))

(defn rel
  [ent sub-ent type opts]
  (let [var-name (-> sub-ent meta :name)
        cur-ns *ns*]
    (assoc-in ent [:rel (name var-name)]
              (delay
               (let [resolved (ns-resolve cur-ns var-name)
                     sub-ent (when resolved (deref sub-ent))]
                 (when-not (map? sub-ent)
                   (throw (Exception. (format "Entity used in relationship does not exist: %s" (name var-name)))))
                 (create-rel ent sub-ent type opts))))))

(defn get-rel [ent sub-ent]
  (let [sub-name (if (map? sub-ent)
                   (:name sub-ent)
                   sub-ent)]
    (force (get-in ent [:rel sub-name]))))

(defn default-fk-name
  "Determines the default name to use for a foreign key."
  [ent]
  (if (string? ent)
    (keyword (str ent "_id"))
    (keyword (str (:table ent) "_id"))))

(defmacro has-one
  "Add a has-one relationship for the given entity. It is assumed that the foreign key
  is on the sub-entity with the format table_id: user.id = address.user_id
  Opts can include a key for :fk to explicitly set the foreign key.

  (has-one users address {:fk :addressID})"
  [ent sub-ent & [opts]]
  `(rel ~ent (var ~sub-ent) :has-one ~opts))

(defmacro belongs-to
  "Add a belongs-to relationship for the given entity. It is assumed that the foreign key
  is on the current entity with the format sub-ent-table_id: email.user_id = user.id.
  Opts can include a key for :fk to explicitly set the foreign key.

  (belongs-to users email {:fk :emailID})"
  [ent sub-ent & [opts]]
  `(rel ~ent (var ~sub-ent) :belongs-to ~opts))

(defmacro has-many
  "Add a has-many relation for the given entity. It is assumed that the foreign key
  is on the sub-entity with the format table_id: user.id = email.user_id
  Opts can include a key for :fk to explicitly set the foreign key.
  
  (has-many users email {:fk :emailID})"
  [ent sub-ent & [opts]]
  `(rel ~ent (var ~sub-ent) :has-many ~opts))

(defmacro many-to-many
  "Add a many-to-many relation for the given entity.  It is assumed that a join
   table is used to implement the relationship and that the foreign keys are in
   the join table."
  [ent sub-ent join-table & [opts]]
  `(rel ~ent (var ~sub-ent) :many-to-many
                 (assoc ~opts
                   :join-table ~join-table
                   :lfk (delay (:lfk ~opts (default-fk-name ~ent)))
                   :rfk (delay (:rfk ~opts (default-fk-name ~sub-ent))))))

(defn entity-fields
  "Set the fields to be retrieved by default in select queries for the
  entity."
  [ent & fields]
  (update-in ent [:fields] concat (map #(eng/prefix ent %) fields)))

(defn table
  "Set the name of the table and an optional alias to be used for the entity. 
  By default the table is the name of entity's symbol."
  [ent t & [alias]]
  (let [tname (if (or (keyword? t)
                      (string? t))
                (name t)
                (if alias
                  t
                  (throw (Exception. "Generated tables must have aliases."))))
        ent (assoc ent :table tname)]
    (if alias
      (assoc ent :alias (name alias))
      ent)))

(defn pk
  "Set the primary key used for an entity. :id by default."
  [ent pk]
  (assoc ent :pk (keyword pk)))

(defn database
  "Set the database connection to be used for this entity."
  [ent db]
  (assoc ent :db db))

(defn transform
  "Add a function to be applied to results coming from the database"
  [ent func]
  (update-in ent [:transforms] conj func))

(defn prepare
  "Add a function to be applied to records/values going into the database"
  [ent func]
  (update-in ent [:prepares] conj func))

(defmacro defentity
  "Define an entity representing a table in the database, applying any modifications in
  the body."
  [ent & body]
  `(let [e# (-> (create-entity ~(name ent))
                ~@body)]
     (def ~ent e#)))

;;*****************************************************
;; With
;;*****************************************************

(defn- force-prefix [ent fields]
  (for [field fields]
    (if (vector? field)
      [(utils/generated (eng/prefix ent (first field))) (second field)]
      (eng/prefix ent field))))

(defn merge-part [query neue k]
  (update-in query [k] #(if-let [vs (k neue)]
                          (vec (concat % vs))
                          %)))

(defn- merge-query [query neue]
  (let [merged (reduce #(merge-part % neue %2)
                       query
                       [:fields :group :order :where :params :joins :post-queries])]
    (-> merged
        (add-aliases (:aliases neue)))))

(defn- sub-query [query sub-ent func]
  (let [neue (select* sub-ent)
        neue (bind-query neue (func neue))
        neue (-> neue
                 (update-in [:fields] #(force-prefix sub-ent %))
                 (update-in [:order] #(force-prefix sub-ent %))
                 (update-in [:group] #(force-prefix sub-ent %)))]
    (merge-query query neue)))

(defn- with-many-to-many
  "Defines the post-query to be used to obtain entities in a many-to-many
   relationship with entities in the current query."
  [rel query ent func opts]
  (let [{:keys [lfk rfk rpk join-table]} rel
        pk (get-in query [:ent :pk])
        table (keyword (eng/table-alias ent))]
    (post-query
     query (partial
            map
            #(assoc % table
                    (select ent
                            (join :inner join-table (= @rfk rpk))
                            (func)
                            (where {@lfk (get % pk)})))))))

(defn- with-later-fn
  "Returns a function to be used to obtain entities in a relationship with
   entities in the current query lazily.  This also allows the entities to be
   retrieved as separate objects.  This function is used for has-many
   relationships."
  [table ent func known unknown]
  (partial
   map #(assoc % table
               (select ent (func)
                       (where {unknown (get % known)})))))

(defn- with-one-later-fn
  "Returns a function to be used to obtain entities in a relationship with
   entities in the current query lazily.  This also allows the entities to be
   retrieved as separate objects.  This function is used for has-one and
   belongs-to relationships."
  [table ent func known unknown]
  (partial
   map #(assoc % table
               (first (select ent (func)
                              (where {unknown (get % known)}))))))

(defn- with-later
  "Defines the post-query to be used to obtain entities in a has-many
   relationship with entities in the current query lazily.  This also allows
   the entities to be retrieved as separate objects."
  [rel query ent func opts]
  (let [fk (:fk rel)
        pk (get-in query [:ent :pk])
        table (keyword (eng/table-alias ent))]
    (post-query query (with-later-fn table ent func pk fk))))

(defn- with-one-later
  "Defines the post-query to be used to obtain entities in has-one or
   belongs-to relationships with entities in the current query lazily.  This
   also allows the entities to be retrieved as separate objects."
  [rel query ent func opts]
  (let [fk (:fk rel)
        pk (get-in query [:ent :pk])
        table (keyword (eng/table-alias ent))]
    (post-query query (with-one-later-fn table ent func pk fk))))

(defn- with-now
  "Defines the join to be used to obtain entries in has-one or belongs-to
   relationships with entities in the current query eagerly.  In this case,
   columns in the joined tables are included in the primary entity object."
  [rel query ent func opts]
  (let [table (if (:alias rel)
                [(:table ent) (:alias ent)]
                (:table ent))
        query (join query table (= (:pk rel) (:fk rel)))]
    (sub-query query ent func)))

(defn- with-has-one
  "Defines the post-query or join to be used to obtain entities in a has-one
   relationship with entities in the current query.  If the :later option is
   enabled then the entities will be retrieved lazily and will be returned as
   separate objects.  Otherwise, the entities will be retrieved eagerly and the
   columns in the entities will be included in the objects associated with the
   entities in the query."
  [rel query ent func opts]
  (if (:later opts)
    (with-one-later rel query ent func opts)
    (with-now rel query ent func opts)))

(defn- with-belongs-to-later
  "Defines the post-query to be used to obtain entities in a belongs-to
   relationship with entities in the current query.  This also allowd the
   entities to be retrieved as separate objects."
  [rel query ent func opts]
  (let [fkk (:fkk rel)
        pk  (:pk rel)
        table (keyword (eng/table-alias ent))]
    (post-query query (with-one-later-fn table ent func fkk pk))))

(defn- with-belongs-to
  "Defines the post-query or join to be used to obtain entities in a belongs-to
   relationship with entities in the current query.  If the :later option is
   enabled then the entities will be retrieved lazily and will be returned as
   separate objects.  Otherwise, the entities will be retrieved eagerly and the
   columns in the entities will be included in the objects associated with the
   entities in the query."
  [rel query ent func opts]
  (if (:later opts)
    (with-belongs-to-later rel query ent func opts)
    (with-now rel query ent func opts)))

(def ^:private with-handlers
  {:has-many with-later
   :many-to-many with-many-to-many
   :has-one with-has-one
   :belongs-to with-belongs-to})

(defn with*
  "Allows related entities to be fetched along with the query results.  This
   function is identical to korma.core/with* except that it also supports
   many-to-many relationships and lazy loading of objects in has-one and
   belongs-to relationships."
  [query sub-ent func opts]
  (let [rel (get-rel (:ent query) sub-ent)
        handler-fn (get with-handlers (:rel-type rel))]
    (when (nil? handler-fn)
      (throw (IllegalArgumentException.
              (str "unknown relationship type: " (:rel-type rel)))))
    (handler-fn rel query sub-ent func opts)))

(defmacro with
  "Add a related entity to the given select query. If the entity has a relationship
  type of :belongs-to or :has-one, the requested fields will be returned directly in
  the result map. If the entity is a :has-many, a second query will be executed lazily
  and a key of the entity name will be assoc'd with a vector of the results.

  (defentity email (entity-fields :email))
  (defentity user (has-many email))
  (select user
    (with email) => [{:name \"chris\" :email [{email: \"c@c.com\"}]} ...

  With can also take a body that will further refine the relation:
  (select user
     (with address
        (with state)
        (fields :address.city :state.state)
        (where {:address.zip x})))"
  [query ent & body]
  `(with* ~query ~ent (fn [q#]
                        (-> q#
                            ~@body)) {}))

(defmacro with-object
  "Allows related entities to be fetched along with the query results.  This
   macro is identical to korma.core/with except that it also supports lazy
   loading of entities in has-one and belongs-to relationships."
  [query ent & body]
  `(with* ~query ~ent (fn [q#]
                        (-> q#
                            ~@body)) {:later true}))
