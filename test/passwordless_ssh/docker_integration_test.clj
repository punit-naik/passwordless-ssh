(ns ^:docker-integration passwordless-ssh.docker-integration-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [passwordless-ssh.core :as ssh]
    [passwordless-ssh.docker-integration-support :as sim]
    [taoensso.timbre :as log]))


(use-fixtures
  :once
  (fn [f]
    (log/info "Starting docker integration test fixture")
    (try
      (f)
      (finally
        (log/info "Finishing docker integration test fixture")
        (sim/cleanup-compose-cluster!)))))


(deftest docker-cluster-simulation-test
  (log/info "Running docker cluster simulation integration test")
  (cond
    (not (sim/docker-available?))
    (is true "Skipping docker simulation test because Docker is unavailable.")

    :else
    (do
      (testing "docker compose cluster boots"
        (sim/ensure-compose-cluster!))
      (testing "library configures a passwordless SSH mesh"
        (let [summary (ssh/setup-passwordless-mesh! sim/machines)]
          (is (= 3 (:eligible-machines summary)))
          (is (= 1 (:groups summary)))
          (is (every? :applied (:results summary)))))
      (testing "all nodes can ssh to each other without a password"
        (sim/verify-passwordless-ssh!)))))
