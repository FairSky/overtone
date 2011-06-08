(ns overtone.studio.net
  (:use [plasma api]
        [plasma.net peer url address]
        overtone.core
        [overtone.studio sequencer])
  (:require [lamina.core :as lamina]))

(def DEFAULT-PORT 2000)

(def peer* (ref nil))
(def log* (atom []))

(defmethod rpc-handler :pattern-update
  [peer req]
  (swap! log* conj req)
  (let [{:keys [pname pattern]} (first (:params req))]
    (swap! patterns* assoc pname pattern)))

(defn peer-update-pattern
  [con pname pattern]
  (request con :pattern-update [{:pname pname :pattern pattern}]))

(defn peer-listen
  ([] (peer-listen DEFAULT-PORT))
  ([port]
   ;(set-port-forward 2000 "Overtone")
   (let [p (peer {:port port})]
     (dosync (ref-set peer* p)))))

(defn peer-connect
  [host port]
  (peer-connection @peer* (url "plasma" host port)))

(peer-listen)

(comment
(def _ nil)
(def kicks (atom [80 _ 80 80 _ _ 80 _]))

(track :kick kick)
(track-fn :kick (hit-fn kicks))
)
