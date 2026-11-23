(ns knope.diplomat.release-redirect-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [knope.diplomat.release :as release]
            [org.httpkit.server :as server]))

(deftest download-follows-a-redirect-to-the-asset
  (let [stop (server/run-server (fn [req]
                                  (if (= "/asset" (:uri req))
                                    {:status 200 :body "jar bytes"}
                                    {:status 302 :headers {"Location" "/asset"}}))
                                {:port 0})
        base (str "http://localhost:" (:local-port (meta stop)))
        dir  (fs/create-temp-dir {:prefix "knope-release-redirect-test-"})
        dest (str (fs/path dir "knope.jar.tmp"))]
    (try
      (is (nil? (release/download! (assoc release/default-config
                                          :download-url base
                                          :download-deadline 2000)
                                   "v1.2.3" dest)))
      (is (= "jar bytes" (slurp dest)))
      (finally
        (stop)
        (fs/delete-tree dir)))))
