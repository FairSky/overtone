(ns overtone.studio.sequencer
  (:use [overtone util time-utils event]
        [overtone.sc core node]
        [overtone.studio core]))

(def patterns* (atom {}))

(defn pattern
  "Update a pattern."
  [pname pattern]
  (swap! patterns* assoc pname pattern)
  (event :pattern-update :pname pname :pattern pattern))

(def p pattern)

(defn drum-fn
  "A function that takes a ref (something dereferencable) and returns
  a function that will act as a track player.

  The track player fn takes a metronome and an instrument, and it will
  loop forever generating notes by reading from the ref and playing the
  instrument, passing the val in ref as the first argument."
  [m beat ins pname]
  (let [next-bar (get @patterns* pname)
        hit-cnt (count next-bar)
        next-beat (+ beat hit-cnt)
        next-tick (m next-beat)]
    (doall
      (map-indexed
        #(when (and (playing?) %2)
           (at (m (+ beat %1))
               (ins %2)))
        next-bar))
    (apply-at next-tick #'drum-fn [m next-beat ins pname])))

(defn mono-play-fn
  ""
  [m beat ins pname]
  (let [next-bar (get @patterns* pname)
        hit-cnt (count next-bar)
        next-beat (+ beat hit-cnt)
        next-tick (m next-beat)]
    (doall
      (map-indexed
        #(when (and (playing?) %2)
           (let [b (+ beat %1)
                 id (at (m b)
                        (ins %2))]
             (when (some #{"gate"} (:args ins))
               (at (m (inc b))
                   (ctl id :gate 0)))))
        next-bar))
    (apply-at next-tick #'mono-play-fn [m next-beat ins pname])))