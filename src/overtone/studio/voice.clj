(ns overtone.studio.voice
  (:use [overtone.sc node]
        [overtone.util event deps]))

(defn mono-voice
  "Given a synth and a set of default parameter values, create a new
  voice.  Each voice is allocated a group."
  [synth params]
  nil)

; An instrument abstracts the more basic concept of a synthesizer used by
; SuperCollider.  Every instance of an instrument will be placed in the same
; group, so if you later call (kill my-inst) it will be able to stop all the
; instances of that group.  (Likewise for controlling them...)

(defonce voices*  (ref {}))
(defonce voice-group*   (ref nil))

(def MIXER-BOOT-DEPS [:server-ready :studio-setup-completed])
(def DEFAULT-VOLUME 1.0)
(def DEFAULT-PAN 0.0)

; TODO: pull out the default param atom stuff into a separate mechanism
(defn modify-synth-params
  "Update synth parameter value atoms storing the current default settings."
  [s & params-vals]
  (let [params (:params s)]
    (for [[param value] (partition 2 params-vals)]
      (let [val-atom (:value (first (filter #(= (:name %) (name param)) params)))]
        (if val-atom
          (reset! val-atom value)
          (throw (IllegalArgumentException. (str "Invalid control parameter: " param))))))))

(defn reset-synth-defaults
  "Reset a synth to it's default settings defined at definition time."
  [synth]
  (doseq [param (:params synth)]
    (reset! (:value param) (:default param))))

;; Clear and re-create the voice groups after a reset
;; TODO: re-create the voice groups
;;
;(defn reset-voice-groups
;  "Frees all synth notes for each of the current voices."
;  []
;  (doseq [[name inst] @voices*]
;    (group-clear (:instance-group inst))))
;
;(on-sync-event :reset reset-voice-groups ::reset-voices)
