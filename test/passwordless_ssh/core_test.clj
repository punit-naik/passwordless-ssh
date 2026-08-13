(ns passwordless-ssh.core-test
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.test :refer [deftest is testing]]
    [passwordless-ssh.core :as ssh]
    [taoensso.timbre :as log]))


(deftest connection-options-builds-password-and-private-key-auth
  (log/info "Running connection-options unit test")
  (testing "password auth options"
    (is (= {:machine "ubuntu@10.0.0.10"
            :password "secret"}
           (ssh/connection-options {:ssh-user "ubuntu"
                                    :ip "10.0.0.10"
                                    :auth-type "password"
                                    :auth-secret "secret"}))))

  (testing "private key auth expects readable file path"
    (let [tmp-key (java.io.File/createTempFile "passwordless-ssh-test" ".pem")
          _ (spit tmp-key "dummy-key")]
      (.deleteOnExit tmp-key)
      (is (= {:machine "ubuntu@10.0.0.11"
              :identity-file (.getAbsolutePath tmp-key)}
             (ssh/connection-options {:ssh-user "ubuntu"
                                      :ip "10.0.0.11"
                                      :auth-type "private-key"
                                      :auth-secret (.getAbsolutePath tmp-key)}))))
    (is (= "Private key auth expects auth-secret to be a readable file path on the backend host."
           (:error (ssh/connection-options {:ssh-user "ubuntu"
                                            :ip "10.0.0.12"
                                            :auth-type "private-key"
                                            :auth-secret "/missing/key.pem"}))))))


(deftest connection-options-reports-invalid-machine-input
  (log/info "Running invalid connection-options unit test")
  (is (= "Missing ssh-user or ip for machine."
         (:error (ssh/connection-options {:ssh-user ""
                                          :ip "10.0.0.10"
                                          :auth-type "password"
                                          :auth-secret "pw"}))))
  (is (= "Missing auth-secret for password authentication."
         (:error (ssh/connection-options {:ssh-user "ubuntu"
                                          :ip "10.0.0.10"
                                          :auth-type "password"
                                          :auth-secret " "}))))
  (is (= "Unsupported auth-type: token"
         (:error (ssh/connection-options {:ssh-user "ubuntu"
                                          :ip "10.0.0.10"
                                          :auth-type "token"
                                          :auth-secret "pw"})))))


(deftest build-session-supports-private-key-auth
  (log/info "Running build-session private-key unit test")
  (let [private-key-file (io/file (System/getProperty "java.io.tmpdir") "passwordless-ssh-build-session-test-key")]
    (when (.exists private-key-file)
      (.delete private-key-file))
    (let [{:keys [exit err]} (sh/sh "bash" "-lc"
                                    (str "ssh-keygen -t rsa -b 2048 -N '' -f "
                                         (pr-str (.getAbsolutePath private-key-file))
                                         " >/dev/null 2>&1"))]
      (is (zero? exit) err))
    (try
      (let [session (ssh/build-session {:ssh-user "ubuntu"
                                        :ip "10.0.0.11"
                                        :auth-type "private-key"
                                        :auth-secret (.getAbsolutePath private-key-file)})]
        (is (= "no" (.getConfig session "StrictHostKeyChecking")))
        (is (= "publickey,password,keyboard-interactive"
               (.getConfig session "PreferredAuthentications"))))
      (finally
        (doseq [path [private-key-file (io/file (str (.getAbsolutePath private-key-file) ".pub"))]]
          (when (.exists path)
            (.delete path)))))))


(deftest exec-machine-uses-local-shell-or-jsch-based-on-machine-ip
  (log/info "Running exec-machine unit test")
  (testing "localhost command execution uses local shell"
    (let [invocation (atom nil)]
      (with-redefs [sh/sh (fn [& args]
                            (reset! invocation args)
                            {:exit 0 :out "ok" :err ""})]
        (is (= 0 (:exit (ssh/exec-machine {:ip "localhost"} "echo hello"))))
        (is (= ["bash" "-lc" "echo hello"] @invocation)))))

  (testing "localhost execution falls back to ssh when the configured user differs"
    (let [invocation (atom nil)]
      (with-redefs [ssh/exec-remote-command (fn [machine cmd]
                                              (reset! invocation {:machine machine
                                                                  :cmd cmd})
                                              {:exit 0 :out "remote-ok" :err ""})]
        (let [result (ssh/exec-machine {:ip "127.0.0.1"
                                        :ssh-user "some-other-user"
                                        :auth-type "password"
                                        :auth-secret "pw"}
                                       "hostname")]
          (is (= 0 (:exit result)))
          (is (= {:ip "127.0.0.1"
                  :ssh-user "some-other-user"
                  :auth-type "password"
                  :auth-secret "pw"}
                 (:machine @invocation)))
          (is (= "hostname" (:cmd @invocation)))))))

  (testing "remote execution delegates to jsch command executor"
    (let [invocation (atom nil)]
      (with-redefs [ssh/exec-remote-command (fn [machine cmd]
                                              (reset! invocation {:machine machine
                                                                  :cmd cmd})
                                              {:exit 0 :out "remote-ok" :err ""})]
        (let [result (ssh/exec-machine {:ip "10.0.0.10"
                                        :ssh-user "root"
                                        :auth-type "password"
                                        :auth-secret "pw"}
                                       "hostname")]
          (is (= 0 (:exit result)))
          (is (= {:ip "10.0.0.10"
                  :ssh-user "root"
                  :auth-type "password"
                  :auth-secret "pw"}
                 (:machine @invocation)))
          (is (= "hostname" (:cmd @invocation))))))))


(deftest exec-machine-handles-invalid-options-and-remote-errors
  (log/info "Running exec-machine error-path unit test")
  (testing "invalid connection options return a shell-style error map"
    (let [result (ssh/exec-machine {:ip "10.0.0.10"
                                    :ssh-user ""
                                    :auth-type "password"
                                    :auth-secret "pw"}
                                   "hostname")]
      (is (= 64 (:exit result)))
      (is (= "Missing ssh-user or ip for machine." (:err result)))))

  (testing "remote exceptions are wrapped into exit 255"
    (with-redefs [ssh/exec-remote-command (fn [_machine _cmd]
                                            (throw (ex-info "boom" {})))]
      (log/with-config (assoc log/*config* :appenders {})
                       (let [result (ssh/exec-machine {:ip "10.0.0.10"
                                                       :ssh-user "root"
                                                       :auth-type "password"
                                                       :auth-secret "pw"}
                                                      "hostname")]
                         (is (= 255 (:exit result)))
                         (is (= "boom" (:err result))))))))


(deftest setup-passwordless-mesh-groups-compatible-machines
  (log/info "Running passwordless mesh grouping unit test")
  (let [calls (atom [])]
    (with-redefs [ssh/setup-passwordless-group-via-ssh! (fn [machines]
                                                          (swap! calls conj machines)
                                                          :ok)]
      (let [result (ssh/setup-passwordless-mesh!
                     [{:ip "10.0.0.1" :ssh-user "ubuntu" :auth-type "password" :auth-secret "cluster-pass"}
                      {:ip "10.0.0.2" :ssh-user "ubuntu" :auth-type "password" :auth-secret "cluster-pass"}
                      {:ip "10.0.0.3" :ssh-user "ubuntu" :auth-type "password" :auth-secret "other-pass"}
                      {:ip "127.0.0.1" :ssh-user "ubuntu" :auth-type "password" :auth-secret "cluster-pass"}])]
        (is (= 3 (:eligible-machines result)))
        (is (= 2 (:groups result)))
        (is (= 1 (count @calls)))
        (is (= [{:ip "10.0.0.1" :ssh-user "ubuntu" :auth-type "password" :auth-secret "cluster-pass"}
                {:ip "10.0.0.2" :ssh-user "ubuntu" :auth-type "password" :auth-secret "cluster-pass"}]
               (first @calls)))
        (is (= 1 (count (filter :applied (:results result)))))
        (is (= 1 (count (filter :skipped (:results result)))))))))


(deftest setup-passwordless-mesh-skips-private-key-groups-with-invalid-path
  (log/info "Running private-key skip unit test")
  (let [calls (atom [])]
    (with-redefs [ssh/setup-passwordless-group-via-ssh! (fn [machines]
                                                          (swap! calls conj machines)
                                                          :ok)]
      (let [result (ssh/setup-passwordless-mesh!
                     [{:ip "10.0.0.21" :ssh-user "ubuntu" :auth-type "private-key" :auth-secret "/missing/key.pem"}
                      {:ip "10.0.0.22" :ssh-user "ubuntu" :auth-type "private-key" :auth-secret "/missing/key.pem"}])
            result-entry (first (:results result))]
        (is (zero? (count @calls)))
        (is (false? (:applied result-entry)))
        (is (true? (:skipped result-entry)))
        (is (= "Private key auth requires auth-secret to point to a readable private key file."
               (:reason result-entry)))))))


(deftest setup-passwordless-mesh-skips-password-groups-without-password
  (log/info "Running password skip unit test")
  (let [calls (atom [])]
    (with-redefs [ssh/setup-passwordless-group-via-ssh! (fn [machines]
                                                          (swap! calls conj machines)
                                                          :ok)]
      (let [result (ssh/setup-passwordless-mesh!
                     [{:ip "10.0.0.31" :ssh-user "ubuntu" :auth-type "password" :auth-secret " "}
                      {:ip "10.0.0.32" :ssh-user "ubuntu" :auth-type "password" :auth-secret nil}])
            result-entry (first (:results result))]
        (is (zero? (count @calls)))
        (is (false? (:applied result-entry)))
        (is (true? (:skipped result-entry)))
        (is (= "Password auth requires auth-secret to be set for passwordless SSH setup."
               (:reason result-entry)))))))


(deftest setup-passwordless-group-via-ssh-configures-known-hosts-and-authorized-keys
  (log/info "Running passwordless group orchestration unit test")
  (let [calls (atom [])]
    (with-redefs [ssh/ensure-ssh-keypair! (fn [machine]
                                            (swap! calls conj [:keypair machine]))
                  ssh/read-public-key! (fn [machine]
                                         (swap! calls conj [:read machine])
                                         (str "pub-" (:ip machine)))
                  ssh/authorize-known-host! (fn [source-machine target-machine]
                                              (swap! calls conj [:known-host source-machine target-machine]))
                  ssh/authorize-public-key! (fn [machine public-key]
                                              (swap! calls conj [:authorize machine public-key]))]
      (is (= :ok
             (ssh/setup-passwordless-group-via-ssh!
               [{:hostname "node-1" :ip "10.0.0.1"}
                {:hostname "node-2" :ip "10.0.0.2"}])))
      (is (= [[:keypair {:hostname "node-1" :ip "10.0.0.1"}]
              [:read {:hostname "node-1" :ip "10.0.0.1"}]
              [:keypair {:hostname "node-2" :ip "10.0.0.2"}]
              [:read {:hostname "node-2" :ip "10.0.0.2"}]
              [:known-host {:hostname "node-1" :ip "10.0.0.1"} {:hostname "node-1" :ip "10.0.0.1"}]
              [:known-host {:hostname "node-1" :ip "10.0.0.1"} {:hostname "node-2" :ip "10.0.0.2"}]
              [:known-host {:hostname "node-2" :ip "10.0.0.2"} {:hostname "node-1" :ip "10.0.0.1"}]
              [:known-host {:hostname "node-2" :ip "10.0.0.2"} {:hostname "node-2" :ip "10.0.0.2"}]
              [:authorize {:hostname "node-1" :ip "10.0.0.1"} "pub-10.0.0.1"]
              [:authorize {:hostname "node-1" :ip "10.0.0.1"} "pub-10.0.0.2"]
              [:authorize {:hostname "node-2" :ip "10.0.0.2"} "pub-10.0.0.1"]
              [:authorize {:hostname "node-2" :ip "10.0.0.2"} "pub-10.0.0.2"]]
             @calls)))))


(deftest ssh-bootstrap-helper-functions-throw-on-command-failure
  (log/info "Running SSH helper failure unit tests")
  (testing "ensure-ssh-keypair! throws when command fails"
    (with-redefs [ssh/exec-machine (fn [_machine _cmd] {:exit 1 :out "" :err "keygen failed"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unable to create/read SSH keypair on machine."
            (ssh/ensure-ssh-keypair! {:ip "10.0.0.1"})))))

  (testing "authorize-known-host! throws when command fails"
    (with-redefs [ssh/exec-machine (fn [_machine _cmd] {:exit 1 :out "" :err "scan failed"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unable to authorize SSH known host on machine."
            (ssh/authorize-known-host! {:ip "10.0.0.1"}
                                       {:hostname "node-2" :ip "10.0.0.2"})))))

  (testing "read-public-key! throws on command failure"
    (with-redefs [ssh/exec-machine (fn [_machine _cmd] {:exit 1 :out "" :err "cat failed"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unable to read machine public key."
            (ssh/read-public-key! {:ip "10.0.0.1"})))))

  (testing "read-public-key! throws when the key is empty"
    (with-redefs [ssh/exec-machine (fn [_machine _cmd] {:exit 0 :out "   " :err ""})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Machine public key is empty."
            (ssh/read-public-key! {:ip "10.0.0.1"})))))

  (testing "authorize-public-key! throws when command fails"
    (with-redefs [ssh/exec-machine (fn [_machine _cmd] {:exit 1 :out "" :err "append failed"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unable to authorize SSH key on machine."
            (ssh/authorize-public-key! {:ip "10.0.0.1"} "ssh-rsa AAA"))))))


(deftest setup-passwordless-group-captures-bootstrap-failures
  (log/info "Running passwordless group exception unit test")
  (with-redefs [ssh/setup-passwordless-group-via-ssh! (fn [_machines]
                                                        (throw (ex-info "group failed" {})))]
    (log/with-config (assoc log/*config* :appenders {})
                     (let [result (ssh/setup-passwordless-group!
                                    [{:ip "10.0.0.1" :ssh-user "ubuntu" :auth-type "password" :auth-secret "pw"}
                                     {:ip "10.0.0.2" :ssh-user "ubuntu" :auth-type "password" :auth-secret "pw"}])]
                       (is (false? (:applied result)))
                       (is (false? (:skipped result)))
                       (is (= "group failed" (:error result)))))))


(deftest known-host-identifiers-includes-hostname-and-ip-once
  (log/info "Running known-host identifier unit test")
  (let [known-host-identifiers ssh/known-host-identifiers]
    (is (= ["node-1" "10.0.0.1"]
           (vec (known-host-identifiers {:hostname "node-1"
                                         :ip "10.0.0.1"}))))
    (is (= ["10.0.0.1"]
           (vec (known-host-identifiers {:hostname " "
                                         :ip "10.0.0.1"}))))
    (is (= ["node-1"]
           (vec (known-host-identifiers {:hostname "node-1"
                                         :ip "node-1"}))))))


(deftest setup-passwordless-mesh-handles-local-only-machines
  (log/info "Running local-only mesh unit test")
  (is (= {:eligible-machines 0
          :groups 0
          :results []}
         (ssh/setup-passwordless-mesh!
           [{:ip "localhost" :ssh-user "ubuntu" :auth-type "password" :auth-secret "pw"}
            {:ip "127.0.0.1" :ssh-user "ubuntu" :auth-type "password" :auth-secret "pw"}]))))
