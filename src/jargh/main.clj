(ns jargh.main
  (:require [jargh.core :refer [create-uberjar] :as jargh]
            [badigeon.classpath :as classpath]
            [badigeon.compile :as compile]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as string]))

(defn- conj-arg [m k v]
  (update m k conj v))

(def options
  [["-m" "--main MAIN" "Namespace containing a -main function. Implies --manifest Main-Class=MAIN, and -c main"]
   ["-v" "--verbose" "Print out information about what is going into the jar."]
   ["-M" "--manifest KEY=VALUE" "Add keys & values to manifest" :assoc-fn conj-arg]
   ["-a" "--alias ALIAS" "Use these classpath aliases to construct classpath." :assoc-fn conj-arg]
   ["-c" "--compile NS" "AOT compile the given namespace."
    :assoc-fn conj-arg]
   [nil "--compile-to DIR" "Where to put the classes" :default "target/"]
   [nil "--skip-file SKIP-FILE-RE" "Add a regex to the regexes for skipping files in merged jars."
    :assoc-fn conj-arg
    :parse-fn re-pattern
    :default jargh/default-skip-files]
   [nil "--skip-jar SKIP-JAR-RE" "Add a regex to the regexes for skipping jars"
    :assoc-fn conj-arg
    :parse-fn re-pattern
    :default jargh/default-skip-jars]
   [nil "--merge-strategy RE=SYM" "Add a merge strategy for files matching RE.
This is a function [target input] which merges input into target."
    :parse-fn (fn [v]
                (let [ep (.lastIndexOf v "=")]
                  [(re-pattern (subs v 0 ep))
                   (requiring-resolve
                    (read-string (subs v (inc ep))))]))
    :assoc-fn conj-arg
    :default jargh/default-merge-strategies
    ]
   ["-h" "--help" "This help"]
   ])

(defn- in-thread-group [f & args]
  (let [exception (atom nil)

        group (proxy [ThreadGroup] ["Build group"]
                (^void uncaughtException [^Thread t ^Throwable e]
                 (binding [*out* *err*]
                   (println "Compile error: " (.getMessage e)))
                 (reset! exception e)))

        thread (Thread. group #(apply f args))
        ]
    (.start thread)
    (.join thread)
    (when-let [e @exception]
      (throw e))))

(defn -main [& args]
  (let [{:keys [options arguments summary errors]}
        (parse-opts args options)]
    (if (or errors (:help options))
      (binding [*out* *err*]
        (doseq [e errors] (println e))
        (println summary))
      
      (let [{:keys [main verbose manifest alias compile compile-to
                    skip-jar skip-file merge-strategy]} options
            [target] arguments
            aliases (map read-string alias)
            compile (map read-string compile)

            target (or target "target/uber.jar")
            classpath (classpath/make-classpath {:aliases aliases})
            
            main (when main (read-string main))
            manifest (cond-> (into {} (for [m manifest :let [[a b] (string/split m #"=")]]
                                        [(keyword a) b]))
                       main (assoc :Main-Class main))

            compile (cond-> compile
                      main (conj main))
            ]
        (binding [jargh/*log* (if verbose
                                *err*
                                jargh/*log*)]
          (when verbose
            (jargh/log "classpath")
            (doseq [entry (string/split classpath #":")]
              (jargh/log "  " entry)))
          
          (jargh/log "Manifest:" manifest)
          
          (when (seq compile)
            (jargh/log "Compiling" compile)
            
            (in-thread-group
             #(compile/compile
               (set compile)
               {:compile-path compile-to
                :compiler-options {:disable-locals-clearing false
                                   :elide-meta [:doc :file :line :added]
                                   :direct-linking true}
                :classpath classpath})))
          
          (create-uberjar
           target
           :classpath classpath
           :manifest manifest
           :verbose verbose
           :skip-files skip-file
           :skip-jars skip-jar
           :merge-strategy (jargh/regex-list-merge-strategy merge-strategy)
           ))))))

