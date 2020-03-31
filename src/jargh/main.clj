(ns jargh.main
  (:require [jargh.core :refer [create-uberjar]]
            [badigeon.classpath :as classpath]
            [badigeon.compile :as compile]
            [clojure.tools.cli :refer [parse-opts]]))

(def options
  [["-m" "--main MAIN" "Namespace containing a -main function. Implies --manifest Main-Class=MAIN."]
   ["-v" "--verbose" "Print out information about what is going into the jar."]
   ["-mf" "--manifest KEY=VALUE" :update-fn conj]
   ["-a" "--alias" "Use these classpath aliases to construct classpath." :update-fn conj]
   ["-c" "--compile" "AOT compile the namespace given to --main"]])

(defn- in-thread-group [f & args]
  (let [exception (atom nil)

        group (proxy [ThreadGroup] ["Build group"]
                (^void uncaughtException [^Thread t ^Throwable e]
                 (println (on-red (white "Build error: " (bold (.getMessage e)))))
                 (reset! exception e)))

        thread (Thread. group #(apply f args))
        ]
    (.start thread)
    (.join thread)
    (when-let [e @exception]
      (throw e))))

(defn -main [args]
  (let [{:keys [options arguments summary errors]}
        (parse-opts args options)]
    (if errors
      (binding [*out* System/err]
        (doseq [e errors] (println e))
        (println summary))

      (let [{:keys [main verbose manifest alias compile]} options
            [target] arguments
            target (or target "target/uber.jar")
            classpath (classpath/make-classpath {:aliases (map read-string alias)})

            main (when main (read-string main))
            manifest (cond-> (into {} (for [m manifest :let [[a b] (string/split m "=")]]
                                        [(keyword a) b]))
                       main (assoc :Main-Class main))
            ]

        (when (and main compile)
          (in-thread-group
           #(compile/compile
             [main]
             {:compile-path "target/classes"
              :compiler-options {:disable-locals-clearing false
                                 :elide-meta [:doc :file :line :added]
                                 :direct-linking true}
              :classpath classpath})))
        
        (create-uberjar
         target
         :classpath classpath
         :manifest manifest
         :verbose verbose))))
  
  
  )
