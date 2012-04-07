(ns
  ^{:doc "The ugen functions create a data structure representing a synthesizer
         graph that can be executed on the synthesis server.  This is the logic
         to \"compile\" these clojure data structures into a form that can be
         serialized by the byte-spec defined in synthdef.clj."
    :author "Jeff Rose"}
  overtone.sc.synth
  (:use [overtone.util helpers old-contrib]
        [overtone.util event counters seq]
        [overtone.music time]
        [overtone.sc.machinery.ugen fn-gen defaults common specs sc-ugen]
        [overtone.sc.machinery synthdef]
        [overtone.sc bindings ugens server node])
  (:require [overtone.at-at :as at-at]
            [overtone.util.log :as log]
            [clojure.set :as set]))

;; ### Synth
;;
;; A Synth is a collection of unit generators that run together. They can be
;; addressed and controlled by commands to the synthesis engine. They read
;; input and write output to global audio and control buses. Synths can have
;; their own local controls that are set via commands to the server.

(defn- ugen-index [ugens ugen]
  (ffirst (filter (fn [[i v]]
                    (= (:id v) (:id ugen)))
                  (indexed ugens))))

; Gets the group number (source) and param index within the group (index)
; from the params that are grouped by rate like this:
;
;[[{:name :freq :default 440.0 :rate  1} {:name :amp :default 0.4 :rate  1}]
; [{:name :adfs :default 20.23 :rate  2} {:name :bar :default 8.6 :rate  2}]]
(defn- param-input-spec [grouped-params param-proxy]
  (let [param-name (:name param-proxy)
        ctl-filter (fn [[idx ctl]] (= param-name (:name ctl)))
        [[src group] foo] (take 1 (filter
                              (fn [[src grp]]
                                (not (empty?
                                       (filter ctl-filter (indexed grp)))))
                              (indexed grouped-params)))
        [[idx param] bar] (take 1 (filter ctl-filter (indexed group)))]
    (if (or (nil? src) (nil? idx))
      (throw (IllegalArgumentException. (str "Invalid parameter name: " param-name ". Please make sure you have named all parameters in the param map in order to use them inside the synth definition."))))
    {:src src :index idx}))

(defn- inputs-from-outputs [src src-ugen]
  (for [i (range (count (:outputs src-ugen)))]
    {:src src :index i}))

; NOTES:
; * *All* inputs must refer to either a constant or the output of another
;   UGen that is higher up in the list.
(defn- with-inputs
  "Returns ugen object with its input ports connected to constants and upstream
  ugens according to the arguments in the initial definition."
  [ugen ugens constants grouped-params]
  (when-not (contains? ugen :args)
    (throw (IllegalArgumentException.
             (format "The %s ugen does not have any arguments."
                     (:name ugen)))))
  (when-not (every? #(or (sc-ugen? %) (number? %) (string? %)) (:args ugen))
    (throw (IllegalArgumentException.
             (format "The %s ugen has an invalid argument: %s"
                     (:name ugen)
                     (first (filter
                              #(not (or (sc-ugen? %) (number? %)))
                              (:args ugen)))))))

  (let [inputs (flatten
                 (map (fn [arg]
                        (cond
                          ; constant
                          (number? arg)
                          {:src -1 :index (index-of constants (float arg))}

                          ; control
                          (control-proxy? arg)
                          (param-input-spec grouped-params arg)

                          ; output proxy
                          (output-proxy? arg)
                          (let [src (ugen-index ugens (:ugen arg))]
                            {:src src :index (:index arg)})

                          ; child ugen
                          (sc-ugen? arg)
                          (let [src (ugen-index ugens arg)
                                updated-ugen (nth ugens src)]
                            (inputs-from-outputs src updated-ugen))))
                      (:args ugen)))
        ugen (assoc ugen :inputs inputs)]
    (when-not (every? (fn [{:keys [src index]}]
                    (and (not (nil? src))
                         (not (nil? index))))
                  (:inputs ugen))
      (throw (Exception.
               (format "Cannot connect ugen arguments for %s ugen with args: %s" (:name ugen) (str (seq (:args ugen)))))))

    ugen))

; TODO: Currently the output rate is hard coded to be the same as the
; computation rate of the ugen.  We probably need to have some meta-data
; capabilities for supporting varying output rates...
(defn- with-outputs
  "Returns a ugen with its output port connections setup according to the spec."
  [ugen]
  {:post [(every? (fn [val] (not (nil? val))) (:outputs %))]}
  (if (contains? ugen :outputs)
    ugen
    (let [spec (get-ugen (:name ugen))
          num-outs (or (:n-outputs ugen) 1)
          outputs (take num-outs (repeat {:rate (:rate ugen)}))]
      (assoc ugen :outputs outputs))))

; IMPORTANT NOTE: We need to add outputs before inputs, so that multi-channel
; outputs can be correctly connected.
(defn- detail-ugens
  "Fill in all the input and output specs for each ugen."
  [ugens constants grouped-params]
  (let [constants (map float constants)
        outs  (map with-outputs ugens)
        ins   (map #(with-inputs %1 outs constants grouped-params) outs)
        final (map #(assoc %1 :args nil) ins)]
    (doall final)))

(defn- make-control-ugens
  "Controls are grouped by rate, so that a single Control ugen represents
  each rate present in the params.  The Control ugens are always the top nodes
  in the graph, so they can be prepended to the topologically sorted tree.

  Specifically handles control proxies at :tr, :ar, :kr and :ir"
  [grouped-params]
  (loop [done   {}
         todo   grouped-params
         offset 0]
    (if (empty? todo)
      (filter #(not (nil? %))
              [(:ir done) (:tr done) (:ar done) (:kr done)])
      (let [group      (first todo)
            group-rate (:rate (first group))
            group-size (count group)
            ctl-proxy  (case group-rate
                             :tr (trig-control-ugen group-size offset)
                             :ar (audio-control-ugen group-size offset)
                             :kr (control-ugen group-size offset)
                             :ir (inst-control-ugen group-size offset))]

        (recur (assoc done group-rate ctl-proxy) (rest todo) (+ offset group-size))))))

(defn- group-params
  "Groups params by rate.  Groups a list of parameters into a
   list of lists, one per rate."
  [params]
  (let [by-rate (reduce (fn [mem param]
                          (let [rate (:rate param)
                                rate-group (get mem rate [])]
                            (assoc mem rate (conj rate-group param))))
                        {} params)]
    (filter #(not (nil? %1))
            [(:ir by-rate) (:tr by-rate) (:ar by-rate) (:kr by-rate)])))

(def DEFAULT-RATE :kr)

(defn- ensure-param-keys!
  "throws an error if map m doesn't contain the correct keys: :name, :default and :rate"
  [m]
  (when-not (and
             (contains? m :name)
             (contains? m :default)
             (contains? m :rate))
    (throw (IllegalArgumentException. (str "Invalid synth param map. Expected to find the keys :name, :default, :rate, got: " m)))))

(defn- ensure-paired-params!
  "throws an error if list l does not contain an even number of elements"
  [l]
  (when-not (even? (count l))
    (throw (IllegalArgumentException. (str "A synth requires either an even number of arguments in the form [control default]* i.e. [freq 440 vol 0.5] or a list of maps. You passed " (count l) " args: " l)))))

(defn- ensure-vec!
  "throws an error if list l is not a vector"
  [l]
  (when-not (vector? l)
    (throw (IllegalArgumentException. (str "Your synth argument list is not a vector. Instead I found " (type l) ": " l)))))

(defn- mapify-params
  "converts a list of param name val pairs to a param map. If the val of a param
  is a vector, it assumes it's a pair of [val rate] and sets the rate of the
  param accordingly. If the val is a plain number, it sets the rate to
  DEFAULT-RATE. All names are converted to strings"
  [params]
  (for [[p-name p-val] (partition 2 params)]
    (let [param-map
          (if (associative? p-val)
            (merge
              {:name  (str p-name)
               :rate  DEFAULT-RATE} p-val)
            {:name (str p-name)
             :default `(float ~p-val)
             :rate DEFAULT-RATE})]
      (ensure-param-keys! param-map)
      param-map)))

(defn- stringify-names
  "takes a map and converts the val of key :name to a string"
  [m]
  (into {} (for [[k v] m] (if (= :name k) [k (str v)] [k v]))))

;; TODO: Figure out a better way to specify rates for synth parameters
;; perhaps using name post-fixes such as [freq:kr 440]
(defn- parse-params
  "Used by defsynth to parse the param list. Accepts either a vector of
   name default pairs, name [default rate] pairs or a vector of maps:

  (defsynth foo [freq 440] ...)
  (defsynth foo [freq {:default 440 :rate :ar}] ...)

  Returns a vec of param maps"

  [params]
  (ensure-vec! params)
  (ensure-paired-params! params)
  (vec (mapify-params params)))

(defn- make-params
  "Create the param value vector and parameter name vector."
  [grouped-params]
  (let [param-list (flatten grouped-params)
        pvals  (map #(:default %1) param-list)
        pnames (map (fn [[idx param]]
                      {:name (to-str (:name param))
                       :index idx})
                    (indexed param-list))]
    [pvals pnames]))

(defn- ugen-form? [form]
  (and (seq? form)
       (= 'ugen (first form))))

(defn- fastest-rate [rates]
  (REVERSE-RATES (first (reverse (sort (map RATES rates))))))

(defn- special-op-args? [args]
  (some #(or (sc-ugen? %1) (keyword? %1)) args))

(defn- find-rate [args]
  (fastest-rate (map #(cond
                        (sc-ugen? %1) (REVERSE-RATES (:rate %1))
                        (keyword? %1) :kr)
                     args)))

;;  For greatest efficiency:
;;
;;  * Unit generators should be listed in an order that permits efficient reuse
;;  of connection buffers, so use a depth first topological sort of the graph.

; NOTES:
; * The ugen tree is turned into a ugen list that is sorted by the order in
; which nodes should be processed.  (Depth first, starting at outermost leaf
; of the first branch.
;
; * params are sorted by rate, and then a Control ugen per rate is created
; and prepended to the ugen list
;
; * finally, ugen inputs are specified using their index
; in the sorted ugen list.
;
;  * No feedback loops are allowed. Feedback must be accomplished via delay lines
;  or through buses.
;
(defn synthdef
  "Transforms a synth definition (ugen-graph) into a form that's ready to save
  to disk or send to the server.

    (synthdef \"pad-z\" [
  "
  [sname params ugens constants]
  (let [grouped-params (group-params params)
       [params pnames] (make-params grouped-params)
        with-ctl-ugens (concat (make-control-ugens grouped-params) ugens)
        detailed (detail-ugens with-ctl-ugens constants grouped-params)]
    (with-meta {:name (str sname)
                :constants constants
                :params params
                :pnames pnames
                :ugens detailed}
               {:type :overtone.sc.machinery.synthdef/synthdef})))

(defn- control-proxies
  "Returns a list of param name symbols and control proxies"
  [params]
  (mapcat (fn [param] [(symbol (:name param))
                      `(control-proxy ~(:name param) ~(:default param) ~(:rate param))])
          params))

(defn- gen-synth-name
  "Auto generate an anonymous synth name. Intended for use in synths that have not
   been defined with an explicit name. Has the form \"anon-id\" where id is a unique
   integer across all anonymous synths."
  []
  (str "anon-" (next-id ::anonymous-synth)))

(defn normalize-synth-args
  "Pull out and normalize the synth name, parameters, control proxies and the ugen form
   from the supplied arglist resorting to defaults if necessary."
  [args]
  (let [[sname args] (cond
                       (or (string? (first args))
                           (symbol? (first args))) [(str (first args)) (rest args)]
                           :default                    [(gen-synth-name) args])

        [params ugen-form] (if (vector? (first args))
                             [(first args) (rest args)]
                             [[] args])
        param-proxies (control-proxies params)]
    [sname params param-proxies ugen-form]))

(defn gather-ugens-and-constants
  "Traverses a ugen tree and returns a vector of two sets [#{ugens} #{constants}]."
  [root]
  (if (seq? root)
    (reduce
      (fn [[ugens constants] ugen]
        (let [[us cs] (gather-ugens-and-constants ugen)]
          [(set/union ugens us)
           (set/union constants cs)]))
      [#{} #{}]
      root)
    (let [args (:args root)
          cur-ugens (filter sc-ugen? args)
          cur-ugens (filter (comp not control-proxy?) cur-ugens)
          cur-ugens (map #(if (output-proxy? %)
                            (:ugen %)
                            %) cur-ugens)
          cur-consts (filter number? args)
          [child-ugens child-consts] (gather-ugens-and-constants cur-ugens)
          ugens (conj (set child-ugens) root)
          constants (set/union (set cur-consts) child-consts)]
      [ugens (vec constants)])))

(defn- ugen-children
  "Returns the children (arguments) of this ugen that are themselves upstream ugens."
  [ug]
  (mapcat
    #(cond
       (seq? %) %
       (output-proxy? %) [(:ugen %)]
       :default [%])
    (filter
      (fn [arg]
        (and (not (control-proxy? arg))
             (or (sc-ugen? arg)
                 (and (seq? arg)
                      (every? sc-ugen? arg)))))
      (:args ug))))

(defn topological-sort-ugens
  "Sort into a vector where each node in the directed graph of ugens will always
  be preceded by its upstream dependencies."
  [ugens]
  (loop [ugens ugens
         ; start with leaf nodes that don't have any dependencies
         leaves (set (filter (fn [ugen]
                          (every?
                            #(or (not (sc-ugen? %))
                                 (control-proxy? %))
                            (:args ugen)))
                        ugens))
         sorted-ugens []
         rec-count 0]

    ; bail out after 1000 iterations, either a bug in this code, or a bad synth graph
    (when (= 1000 rec-count)
      (throw (Exception. "Invalid ugen tree passed to topological-sort-ugens, maybe you have cycles in the synthdef...")))

    (if (empty? leaves)
      sorted-ugens
      (let [last-ugen (last sorted-ugens)
            ; try to always place the downstream ugen from the last-ugen if all other
            ; deps are satisfied, which keeps internal buffers in cache as long as possible
            next-ugen (first (filter #((set (ugen-children %)) last-ugen) leaves))
            [next-ugen leaves] (if next-ugen
                                 [next-ugen (disj leaves next-ugen)]
                                 [(first leaves) (next leaves)])
            sorted-ugens (conj sorted-ugens next-ugen)
            sorted-ugen-set (set sorted-ugens)
            ugens (set/difference ugens sorted-ugen-set leaves)
            leaves (set
                     (reduce
                       (fn [rleaves ug]
                         (let [children (ugen-children ug)]
                           (if (set/subset? children sorted-ugen-set)
                             (conj rleaves ug)
                             rleaves)))
                       leaves
                       ugens))]
        (recur ugens leaves sorted-ugens (inc rec-count))))))

(defmacro pre-synth
  "Resolve a synth def to a list of its name, params, ugens (nested if
   necessary) and constants. Sets the lexical bindings of the param
   names to control proxies within the synth definition"
  [& args]
  (let [[sname params param-proxies ugen-form] (normalize-synth-args args)]
    `(let [~@param-proxies]
          (binding [*ugens* []
                    *constants* #{}]
            (let [[ugens# constants#] (gather-ugens-and-constants
                                        (with-overloaded-ugens ~@ugen-form))
                  ugens# (topological-sort-ugens ugens#)
                  main-tree# (set ugens#)
                  side-tree# (filter #(not (main-tree# %)) *ugens*)
                  ugens# (concat ugens# side-tree#)
                  constants# (into [] (set (concat constants# *constants*)))]
       [~sname ~params ugens# constants#])))))

(defrecord Synth [name ugens sdef args instance-fn])

(defmacro synth
  "Define a SuperCollider synthesizer using the library of ugen
  functions provided by overtone.sc.ugen.  This will return a
  record which can be used to trigger the synthesizer with (play my-synth)
  or to create a voice.
  "
  [& args]
  `(let [[sname# params# ugens# constants#] (pre-synth ~@args)
         sdef# (synthdef sname# params# ugens# constants#)
         arg-names# (map :name params#)
         instance-fn# (apply comp (map :instance-fn (filter :instance-fn (map meta ugens#))))
         srec# (map->Synth
                  {:name sname#
                   :ugens ugens#
                   :sdef sdef#
                   :args arg-names#
                   :instance-fn instance-fn#})]
     (load-synthdef sdef#)
     (event :new-synth :synth srec#)
     srec#))

(defn synth-form
  "Internal function used to prepare synth meta-data."
  [s-name s-form]
  (let [[s-name s-form] (name-with-attributes s-name s-form)
        _               (when (not (symbol? s-name))
                          (throw (IllegalArgumentException. (str "You need to specify a name for your synth using a symbol"))))
        params          (first s-form)
        params          (parse-params params)
        ugen-form       (concat '(do) (next s-form))
        param-names     (list (vec (map #(symbol (:name %)) params)))
        md              (assoc (meta s-name)
                          :name s-name
                          :type ::synth
                          :arglists (list 'quote param-names))]
    [(with-meta s-name md) params ugen-form]))

(defmacro defsynth
  "Define a named synthesizer design.  The synth definition
  will be loaded immediately, and a :new-synth event will be emitted.

  (defsynth foo [freq 440]
    (sin-osc freq))

  is equivalent to:

  (def foo
    (synth [freq 440] (sin-osc freq)))

  A doc string can also be included:
  (defsynth bar
    \"The phatest space pad ever!\"
    [] (...))
  "
  [s-name & s-form]
  (let [[s-name params ugen-form] (synth-form s-name s-form)]
    `(def ~s-name (synth ~s-name ~params ~ugen-form))))

(defn synth?
  "Returns true if s is a synth, false otherwise."
  [s]
  (= overtone.sc.synth.Synth (type s)))

(defmethod print-method ::synth [syn w]
  (let [info (meta syn)]
    (.write w (format "#<synth: %s>" (:name info)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Synthdef de-compilation
;;   The eventual goal is to be able to take any SuperCollider scsyndef
;;   file, and produce equivalent clojure code that can be re-edited.

(defn- param-vector [params pnames]
  "Create a synthdef parameter vector."
  (vec (flatten
         (map #(list (symbol (:name %1))
                     (nth params (:index %1)))
              pnames))))

(defn- ugen-form
  "Create a ugen form."
  [ug]
  (let [uname (real-ugen-name ug)
        ugen (get-ugen uname)
        uname (if (and
                    (zero? (:special ug))
                    (not= (:rate-name ug) (:default-rate ugen)))
                (str uname (:rate-name ug))
                uname)
        uname (symbol uname)]
  (apply list uname (:inputs ug))))

(defn- ugen-constant-inputs
  "Replace constant ugen inputs with the constant values."
  [constants ug]
  (assoc ug :inputs
         (map
           (fn [{:keys [src index] :as input}]
             (if (= src -1)
               (nth constants index)
               input))
           (:inputs ug))))

(defn- reverse-ugen-inputs
  "Replace ugen inputs that are other ugens with their generated symbolic name."
  [pnames ugens ug]
  (assoc ug :inputs
         (map
           (fn [{:keys [src index] :as input}]
             (if src
               (let [u-in (nth ugens src)]
                 (if (= "Control" (:name u-in))
                   (nth pnames index)
                   (:sname (nth ugens src))))
               input))
           (:inputs ug))))

; In order to do this correctly is a big project because you also have to
; reverse the process of the various ugen modes.  For example, you need
; to recognize the ugens that have array arguments which will be
; appended, and then you need to gather up the inputs and place them into
; an array at the correct argument location.
(defn synthdef-decompile
  "Decompile a parsed SuperCollider synth definition back into clojure code
  that could be used to generate an identical synth.

  While this probably won't create a synth definition that can directly compile,
  it can still be helpful when trying to reverse engineer a synth."
  [{:keys [name constants params pnames ugens] :as sdef}]
  (let [sname (symbol name)
        param-vec (param-vector params pnames)
        ugens (map #(assoc % :sname %2)
                   ugens
                   (map (comp symbol #(str "ug-" %) char) (range 97 200)))
        ugens (map (partial ugen-constant-inputs constants) ugens)
        pnames (map (comp symbol :name) pnames)
        ugens (map (partial reverse-ugen-inputs pnames ugens) ugens)
        ugens (filter #(not= "Control" (:name %)) ugens)
        ugen-forms (map vector
                     (map :sname ugens)
                     (map ugen-form ugens))]
      (print (format "(defsynth %s %s\n  (let [" sname param-vec))
      (println (ffirst ugen-forms) (second (first ugen-forms)))
      (doseq [[uname uform] (drop 1 ugen-forms)]
        (println "       " uname uform))
      (println (str "       ]\n   " (first (last ugen-forms)) ")"))))

(comment
  ; Some test synths, while shaking out the bugs...
(defsynth foo [] (out 0 (rlpf (saw [220 663]) (x-line:kr 20000 2 1 FREE))))
(defsynth bar [freq 220] (out 0 (rlpf (saw [freq (* 3.013 freq)]) (x-line:kr 20000 2 1 FREE))))
(definst faz [] (rlpf (saw [220 663]) (x-line:kr 20000 2 1 FREE)))
(definst baz [freq 220] (rlpf (saw [freq (* 3.013 freq)]) (x-line:kr 20000 2 1 FREE)))
(run 1 (out 184 (saw (x-line:kr 10000 10 1 FREE))))
)

