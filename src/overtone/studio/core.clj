(ns ^{:doc "Define some higher level mechanisms on top of the low-level SC api for
           creating and triggering synths."
      :author "Jeff Rose and Sam Aaron"}
  overtone.studio.core
  (:use [overtone.util deps event]
        [overtone.util helpers]
        [overtone.sc synth ugens server info node buffer]
        [overtone.sc.machinery defaults]))

;        [overtone.util helpers]
;        [overtone.sc.machinery defaults synthdef]
;        [overtone.sc.machinery.ugen fn-gen defaults sc-ugen]
;        [overtone.sc.machinery.server comms]
;        [overtone.sc server synth ugens envelope node bus]
;        [overtone.sc.util :only [id-mapper]]
;        [overtone.music rhythm time])
;  (:require [overtone.studio fx]
;            [overtone.util.log :as log])

(def ^{:dynamic true} *demo-time* 2000)

(defn- id-able-type?
  [o]
  (or (isa? (type o) :overtone.sc.buffer/buffer)
      (isa? (type o) :overtone.sc.sample/sample)
      (isa? (type o) :overtone.sc.bus/audio-bus)
      (isa? (type o) :overtone.sc.bus/control-bus)))

(defn synth-player
  [synth & args]
    "Returns a player function for a named synth.  Used by (synth ...)
    internally, but can be used to generate a player for a pre-compiled
    synth.  The function generated will accept two optional arguments that
    must come first, the :position and :target (see the node function docs).

    (foo)
    (foo :position :tail :target 0)

    or if foo has two arguments:
    (foo 440 0.3)
    (foo :position :tail :target 0 440 0.3)
    at the head of group 2:
    (foo :position :head :target 2 440 0.3)

    These can also be abbreviated:
    (foo :tgt 2 :pos :head)
    "
    (let [arg-names (map keyword (map :name (:params synth)))
          args (if (and (= 1 (count args))
                        (map? (first args))
                        (not (id-able-type? (first args))))
                 (flatten (seq (first args)))

                 args)
          sgroup @synth-group*
          pos :tail
          args          (map #(if (id-able-type? %)
                                (:id %) %) args)
          defaults (into {} (map (fn [{:keys [name value]}]
                                   [(keyword name) @value])
                                 params))
          arg-map  (arg-mapper args arg-names defaults)
          synth-node (node name arg-map {:position pos :target sgroup })
          synth-node (if (:instance-fn this)
                       ((:instance-fn this) synth-node)
                       synth-node)]
      (when (:instance-fn this)
        (swap! active-synth-nodes* assoc (:id synth-node) synth-node))
      synth-node))

(defn update-tap-data
  [msg]
  (let [[node-id label-id val] (:args msg)
        node                     (get @active-synth-nodes* node-id)
        label                    (get (:tap-labels node) label-id)
        tap-atom                 (get (:taps node) label)]
    (reset! tap-atom val)))

(on-event "/overtone/tap" #'update-tap-data ::handle-incoming-tap-data)

(defmacro run
  "Run an anonymous synth definition for a fixed period of time.  Useful for
  experimentation. Does NOT add  an out ugen - see #'demo for that. You can
  specify a timeout in seconds as the first argument otherwise it defaults to
  *demo-time* ms.

  (run (send-reply (impulse 1) \"/foo\" [1] 43)) ;=> send OSC messages out"
  [& body]
  (let [[demo-time body] (if (number? (first body))
                           [(* 1000 (first body)) (second body)]
                           [*demo-time* (first body)])]
    `(let [s# (synth "audition-synth" ~body)
           note# (s#)]
       (after-delay ~demo-time #(node-free note#))
       note#)))

(defmacro demo
  "Listen to an anonymous synth definition for a fixed period of time.  Useful
  for experimentation.  If the root node is not an out ugen, then it will add
  one automatically.  You can specify a timeout in seconds as the first argument
  otherwise it defaults to *demo-time* ms.

  (demo (sin-osc 440))      ;=> plays a sine wave for *demo-time* ms
  (demo 0.5 (sin-osc 440))  ;=> plays a sine wave for half a second"
  [& body]
  (let [[demo-time body] (if (number? (first body))
                           [(first body) (second body)]
                           [(* 0.001 *demo-time*) (first body)])
        [out-bus body]   (if (= 'out (first body))
                           [(second body) (nth body 2)]
                           [0 body])

        body (list 'out out-bus (list 'hold body demo-time :done 'FREE))]
    `((synth "audition-synth" ~body))))

(defn active-synths
  "Return a seq of the actively running synth nodes.  If a synth or inst are passed as the filter it will only return nodes of that type.

    (active-synths) ; => [{:type synth :name \"mixer\" :id 12}
                          {:type synth :name \"my-synth\" :id 24}]
    (active-synths my-synth) ; => [{:type synth :name \"my-synth\" :id 24}]
  "
  [& [synth-filter]]
  (let [active-nodes (filter #(= overtone.sc.node.SynthNode (type %))
                             (vals @active-synth-nodes*))]
    (if synth-filter
      (filter #(= (:name synth-filter) (:name %)) active-nodes)
      active-nodes)))

