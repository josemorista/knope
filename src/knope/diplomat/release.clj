(ns knope.diplomat.release
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(def default-config
  "Timeouts are in milliseconds."
  {:api-url           "https://api.github.com"
   :download-url      "https://github.com"
   :repo              "josemorista/knope"
   :connect-timeout   10000
   :lookup-timeout    15000
   :download-deadline 30000})

(def ^:private download-failed {:error/code :update/download-failed})

(defn- client
  "Redirects are followed because GitHub serves release assets from another host."
  [config]
  (http/client {:connect-timeout  (:connect-timeout config)
                :follow-redirects :normal}))

(defn- tag-name [body]
  (try
    (let [tag (get (json/parse-string body) "tag_name")]
      (when (string? tag) tag))
    (catch Exception _ nil)))

(defn latest-tag
  "Runs under the same kind of deadline as download!, because the request
  timeout stops once the headers arrive."
  [{:keys [api-url repo lookup-timeout] :as config}]
  (let [body   (atom nil)
        work   (future
                 (try
                   (let [{:keys [status] :as response}
                         (http/get (str api-url "/repos/" repo "/releases/latest")
                                   {:client  (client config)
                                    :headers {"Accept"     "application/vnd.github+json"
                                              "User-Agent" "knope"}
                                    :timeout lookup-timeout
                                    :as      :stream
                                    :throw   false})
                         tag (with-open [in (reset! body (:body response))]
                               (when (= 200 status) (tag-name (slurp in))))]
                     (if tag
                       {:ok tag}
                       {:error/code :update/no-release}))
                   (catch java.io.IOException _
                     {:error/code :update/unreachable})))
        result (deref work lookup-timeout ::deadline)]
    (if (= ::deadline result)
      (do (some-> ^java.io.InputStream @body .close)
          (future-cancel work)
          {:error/code :update/unreachable})
      result)))

(defn download!
  "The request timeout stops once the headers arrive, so the whole download
  runs under a deadline; past it the body stream is closed."
  [{:keys [download-url repo download-deadline] :as config} tag dest]
  (let [body (atom nil)
        work (future
               (try
                 (let [{:keys [status] :as response}
                       (http/get (str download-url "/" repo "/releases/download/" tag "/knope.jar")
                                 {:client  (client config)
                                  :headers {"User-Agent" "knope"}
                                  :timeout download-deadline
                                  :as      :stream
                                  :throw   false})]
                   (with-open [in (reset! body (:body response))]
                     (if (= 200 status)
                       (do (io/copy in (io/file (str dest))) nil)
                       download-failed)))
                 (catch Exception _ download-failed)))
        result (deref work download-deadline ::deadline)]
    (if (= ::deadline result)
      (do (some-> ^java.io.InputStream @body .close)
          (future-cancel work)
          download-failed)
      result)))
