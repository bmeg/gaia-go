(ns protograph.core
  (:require
   [clojure.string :as string]
   [cheshire.core :as json]
   [taoensso.timbre :as log]
   [clojure.tools.cli :as cli]
   [protograph.kafka :as kafka])
  (:import
   [protograph Protograph ProtographEmitter]))

(defn load
  [path]
  (Protograph/loadProtograph path))

(defn emitter
  [emit-vertex emit-edge]
  (reify ProtographEmitter
    (emitVertex [_ vertex] (emit-vertex vertex))
    (emitEdge [_ edge] (emit-edge edge))))

(defn process
  [protograph emit label data]
  (.processMessage protograph emit label data))

(defn kafka-emitter
  [producer vertex-topic edge-topic]
  (emitter
   (fn [vertex]
     (kafka/send producer vertex-topic (Protograph/writeJSON vertex)))
   (fn [edge]
     (kafka/send producer edge-topic (Protograph/writeJSON edge)))))

(defn transform-message
  [protograph emit message]
  (let [label (kafka/topic->label (.topic message))
        data (Protograph/readJSON (.value message))]
    (process protograph emit label data)))

(defn transform-kafka
  [config protograph consumer producer]
  (let [prefix (get-in config [:protograph :prefix])
        vertex-topic (str prefix ".Vertex")
        edge-topic (str prefix ".Edge")
        emit (kafka-emitter producer vertex-topic edge-topic)]
    (kafka/consume
     consumer
     (partial transform-message protograph emit))))

(defn transform-topics
  [config protograph topics]
  (let [host (get-in config [:kafka :host])
        group-id (get-in config [:kafka :consumer :group-id])
        consumer (kafka/consumer host group-id topics)
        producer (kafka/producer host)]
    (transform-kafka config protograph consumer producer)))

(def default-config
  {:protograph
   {:path "../gaia-bmeg/bmeg.protograph.yml"
    :prefix "protograph.bmeg"
    :output "graph.ingested"}
   :kafka kafka/default-config})

(def bmeg-topics
  {:ccle ["ccle.ga4gh.VariantAnnotation" "ccle.ga4gh.CallSet" "ccle.ResponseCurve" "ccle.Biosample" "ccle.GeneExpression" "ccle.ga4gh.Variant"]
   :cna ["ccle.bmeg.cna.CNASegment" "ccle.bmeg.cna.CNACallSet"]
   :ctdd ["ctdd.json.bmeg.phenotype.ResponseCurve" "ctdd.json.bmeg.phenotype.Compound"]
   :cohort ["ccle.Cohort" "gdc.Cohort"]
   :hugo ["hugo.GeneSynonym" "hugo.Pubmed" "hugo.GeneFamily" "hugo.Gene" "hugo.GeneDatabase"]
   :mc3 ["mc3.ga4gh.VariantAnnotation" "mc3.ga4gh.Variant" "mc3.ga4gh.CallSet"]
   :tcga ["tcga.IndividualCohort" "tcga.Biosample" "tcga.GeneExpression" "tcga.Individual"]})

(def parse-args
  [["-p" "--protograph PROTOGRAPH" "path to protograph.yml"
    :default "protograph.yml"]
   ["-k" "--kafka KAFKA" "host for kafka server"
    :default "localhost:9092"]
   ["-t" "--topic TOPIC" "input topic to read from"]
   ["-x" "--prefix PREFIX" "output topic prefix"
    :default "protograph"]])

(defn assoc-env
  [config env]
  (-> config
      (assoc-in [:protograph :prefix] (:prefix env))
      (assoc-in [:kafka :host] (:kafka env))))

(defn -main
  [& args]
  (let [env (:options (cli/parse-opts args parse-args))
        topics (string/split (:topic env) #" +")
        config (assoc-env default-config env)
        protograph (load
                    (or
                     (:protograph env)
                     (get-in config [:protograph :path])))]
    (log/info env)
    (log/info config)
    (transform-topics config protograph topics)))
