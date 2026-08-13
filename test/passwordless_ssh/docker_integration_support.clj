(ns passwordless-ssh.docker-integration-support
  (:require
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [clojure.test :refer [is]]
    [passwordless-ssh.core :as ssh]
    [taoensso.timbre :as log]))


(def compose-file "test-resources/docker-compose.yml")
(def services #{"pssh-node-1" "pssh-node-2" "pssh-node-3"})


(def machines
  [{:hostname "pssh-node-1" :ip "172.30.0.11" :ssh-user "cluster" :auth-type "password" :auth-secret "cluster"}
   {:hostname "pssh-node-2" :ip "172.30.0.12" :ssh-user "cluster" :auth-type "password" :auth-secret "cluster"}
   {:hostname "pssh-node-3" :ip "172.30.0.13" :ssh-user "cluster" :auth-type "password" :auth-secret "cluster"}])


(defn keep-compose-cluster-running?
  "Returns true when the Docker integration cluster should remain running after tests."
  []
  (log/debug "Checking whether Docker test cluster should be kept running")
  (= "true" (some-> (System/getenv "PASSWORDLESS_SSH_DOCKER_KEEP_CLUSTER") str/lower-case)))


(defn docker-available?
  "Returns true when the local Docker CLI and daemon are available to the test run."
  []
  (log/debug "Checking Docker availability")
  (zero? (:exit (shell/sh "bash" "-lc" "docker --version >/dev/null 2>&1"))))


(defn compose-up!
  "Boots the Docker integration cluster with docker compose."
  []
  (log/info "Starting Docker integration cluster" {:compose-file compose-file})
  (shell/sh "bash" "-lc" (str "docker compose -f " compose-file " up -d --build --quiet-pull")))


(defn compose-down!
  "Stops and removes the Docker integration cluster and its attached volumes."
  []
  (log/info "Destroying Docker integration cluster" {:compose-file compose-file})
  (shell/sh "bash" "-lc" (str "docker compose -f " compose-file " down -v --remove-orphans")))


(defn- running-services
  "Returns the set of currently running docker compose services for the integration cluster."
  []
  (let [{:keys [out exit]} (shell/sh "bash" "-lc" (str "docker compose -f " compose-file " ps --services --status running"))]
    (if (zero? exit)
      (->> (str/split-lines out)
           (remove str/blank?)
           set)
      #{})))


(defn await-ssh-ready
  "Waits until every Docker test node can accept the expected SSH-based readiness command."
  []
  (log/info "Waiting for Docker SSH nodes to become ready" {:machines (mapv :hostname machines)})
  (let [readiness-command
        (str "test \"$(id -un)\" = 'cluster' "
             "&& test -d \"$HOME\" "
             "&& mkdir -p \"$HOME/.ssh\" "
             "&& test -w \"$HOME\" "
             "&& test -w \"$HOME/.ssh\"")]
    (loop [attempts-left 60
           consecutive-successes 0]
      (let [ready? (every? #(zero? (:exit (ssh/exec-machine % readiness-command))) machines)]
        (cond
          (and ready? (>= consecutive-successes 1))
          (do
            (log/info "Docker SSH nodes are ready")
            true)

          ready?
          (do
            (log/debug "Docker SSH readiness check succeeded" {:attempts-left attempts-left
                                                               :consecutive-successes consecutive-successes})
            (Thread/sleep 500)
            (recur (dec attempts-left) (inc consecutive-successes)))

          (> attempts-left 1)
          (do
            (log/debug "Docker SSH readiness check failed; retrying" {:attempts-left attempts-left})
            (Thread/sleep 1500)
            (recur (dec attempts-left) 0))

          :else
          (do
            (log/error "Docker SSH nodes failed readiness checks")
            false))))))


(defn ensure-compose-cluster!
  "Starts or reuses the Docker integration cluster and blocks until SSH is ready on all nodes."
  []
  (let [reusing? (and (keep-compose-cluster-running?)
                      (= services (running-services)))]
    (log/info "Ensuring Docker integration cluster is available" {:reusing? reusing?})
    (when-not reusing?
      (compose-down!)
      (let [{:keys [exit err]} (compose-up!)]
        (is (zero? exit) (str "docker compose up failed: " err))))
    (is (= services (running-services)))
    (is (true? (await-ssh-ready)) "SSH daemons did not become ready.")))


(defn cleanup-compose-cluster!
  "Cleans up the Docker integration cluster unless it was explicitly kept for inspection."
  []
  (when-not (keep-compose-cluster-running?)
    (log/info "Cleaning up Docker integration cluster")
    (compose-down!)))


(defn passwordless-ssh-result
  "Runs a passwordless SSH hop check from one Docker test node to another."
  [source target]
  (log/debug "Verifying passwordless SSH hop" {:source (:hostname source)
                                               :target (:hostname target)})
  (ssh/exec-machine
    source
    (str "ssh -o BatchMode=yes -o PasswordAuthentication=no "
         "cluster@" (:ip target) " hostname")))


(defn verify-passwordless-ssh!
  "Asserts that every Docker test node can SSH to every other node without a password."
  []
  (log/info "Verifying full passwordless SSH mesh" {:machines (mapv :hostname machines)})
  (doseq [source machines
          target machines
          :when (not= (:ip source) (:ip target))]
    (let [{:keys [exit out err]} (passwordless-ssh-result source target)]
      (is (zero? exit)
          (str (:hostname source) " -> " (:hostname target) " failed: " err))
      (is (= (:hostname target) (str/trim out))))))
