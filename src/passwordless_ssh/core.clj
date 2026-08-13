(ns passwordless-ssh.core
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [taoensso.timbre :as log])
  (:import
    (com.jcraft.jsch
      ChannelExec
      JSch
      Session)
    (java.io
      ByteArrayOutputStream
      InputStream)))


(def ^:private ssh-connect-timeout-ms 10000)


(defn current-os-user
  "Returns the current OS user name for the running JVM process."
  []
  (System/getProperty "user.name"))


(defn localhost-machine?
  "Returns true when the machine should be treated as the local host."
  [machine]
  (contains? #{"localhost" "127.0.0.1"} (:ip machine)))


(defn build-machine-id
  "Builds a human-readable SSH identifier like user@host for logging and diagnostics."
  [{:keys [ssh-user ip]}]
  (str ssh-user "@" ip))


(defn non-blank-string
  "Returns a trimmed string when the input contains non-whitespace characters."
  [value]
  (when (some? value)
    (let [trimmed (str/trim value)]
      (when (not-empty trimmed)
        trimmed))))


(defn private-key-path
  "Normalizes and validates a private key path, returning it only when readable."
  [secret]
  (when-let [candidate (non-blank-string secret)]
    (let [file (io/file candidate)]
      (when (and (.exists file)
                 (.canRead file))
        candidate))))


(defn shell-quote
  "Safely single-quotes a string for inclusion in a shell command."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))


(defn stream->string
  "Reads an input stream fully into a UTF-8 string."
  [^InputStream stream]
  (let [buffer (byte-array 4096)
        output (ByteArrayOutputStream.)]
    (loop []
      (let [read-count (.read stream buffer 0 (alength buffer))]
        (when (pos? read-count)
          (.write output buffer 0 read-count)
          (recur))))
    (.toString output "UTF-8")))


(defn connection-options
  "Builds normalized SSH connection options for a machine map.

  Supported auth types are:
  - \"password\" with :auth-secret as the password
  - \"private-key\" with :auth-secret as a readable private key path"
  [machine]
  (let [{:keys [ssh-user ip auth-type auth-secret]} machine]
    (log/debug "Building SSH connection options" {:machine (select-keys machine [:hostname :ip :ssh-user :auth-type])})
    (cond
      (or (str/blank? ssh-user) (str/blank? ip))
      (do
        (log/warn "Missing ssh-user or ip for machine" {:machine machine})
        {:error "Missing ssh-user or ip for machine."})

      (= "password" auth-type)
      (if-let [password (non-blank-string auth-secret)]
        {:machine (build-machine-id machine)
         :password password}
        (do
          (log/warn "Missing auth-secret for password authentication" {:machine (select-keys machine [:hostname :ip :ssh-user :auth-type])})
          {:error "Missing auth-secret for password authentication."}))

      (= "private-key" auth-type)
      (if-let [identity-file (private-key-path auth-secret)]
        {:machine (build-machine-id machine)
         :identity-file identity-file}
        (do
          (log/warn "Unreadable private key path for machine" {:machine (select-keys machine [:hostname :ip :ssh-user :auth-type])
                                                               :auth-secret auth-secret})
          {:error "Private key auth expects auth-secret to be a readable file path on the backend host."}))

      :else
      (do
        (log/warn "Unsupported auth-type for machine" {:machine (select-keys machine [:hostname :ip :ssh-user])
                                                       :auth-type auth-type})
        {:error (str "Unsupported auth-type: " auth-type)}))))


(defn build-session
  "Creates and configures a JSch SSH session for the given machine."
  [{:keys [ssh-user ip auth-type auth-secret]}]
  (let [password (non-blank-string auth-secret)
        identity-file (private-key-path auth-secret)
        jsch (JSch.)
        session ^Session (if (= "private-key" auth-type)
                           (do
                             (.addIdentity jsch identity-file)
                             (.getSession jsch ssh-user ip 22))
                           (.getSession jsch ssh-user ip 22))]
    (when (= "password" auth-type)
      (.setPassword session password))
    (.setConfig session "StrictHostKeyChecking" "no")
    (.setConfig session "PreferredAuthentications" "publickey,password,keyboard-interactive")
    session))


(defn exec-remote-command
  "Executes a shell command on a remote machine over SSH."
  [machine cmd]
  (let [session (build-session machine)]
    (log/debug "Executing remote SSH command" {:machine (select-keys machine [:hostname :ip :ssh-user :auth-type])
                                               :command cmd})
    (try
      (.connect session ssh-connect-timeout-ms)
      (let [channel ^ChannelExec (.openChannel session "exec")]
        (try
          (.setInputStream channel nil)
          (.setCommand channel (str "bash -lc " (shell-quote cmd)))
          (let [stdout-stream (.getInputStream channel)
                stderr-stream (.getExtInputStream channel)
                stdout-future (future (stream->string stdout-stream))
                stderr-future (future (stream->string stderr-stream))]
            (.connect channel ssh-connect-timeout-ms)
            (while (not (.isClosed channel))
              (Thread/sleep 25))
            (let [result {:exit (.getExitStatus channel)
                          :out @stdout-future
                          :err @stderr-future}]
              (log/debug "Completed remote SSH command" {:machine (select-keys machine [:hostname :ip :ssh-user :auth-type])
                                                         :command cmd
                                                         :exit (:exit result)})
              result))
          (finally
            (.disconnect channel))))
      (finally
        (.disconnect session)))))


(defn exec-machine
  "Executes a shell command locally for localhost targets, otherwise over SSH."
  [machine cmd]
  (if (and (localhost-machine? machine)
           (let [configured-user (some-> (:ssh-user machine) str/trim not-empty)]
             (or (nil? configured-user)
                 (= configured-user (current-os-user)))))
    (do
      (log/debug "Executing local shell command" {:machine (select-keys machine [:hostname :ip :ssh-user])
                                                  :command cmd})
      (let [result (shell/sh "bash" "-lc" cmd)]
        (log/debug "Completed local shell command" {:machine (select-keys machine [:hostname :ip :ssh-user])
                                                    :command cmd
                                                    :exit (:exit result)})
        result))
    (let [options (connection-options machine)]
      (if-let [error (:error options)]
        (do
          (log/warn "Skipping command because connection options could not be built" {:machine (select-keys machine [:hostname :ip :ssh-user :auth-type])
                                                                                      :command cmd
                                                                                      :error error})
          {:exit 64 :out "" :err error})
        (try
          (exec-remote-command machine cmd)
          (catch Exception ex
            (log/error ex "SSH command execution failed" {:machine (select-keys machine [:hostname :ip :ssh-user :auth-type])
                                                          :command cmd})
            {:exit 255 :out "" :err (.getMessage ex)}))))))


(defn passwordless-group-key
  "Builds the grouping key used to cluster machines by compatible SSH auth details."
  [{:keys [ssh-user auth-type auth-secret]}]
  [ssh-user auth-type (some-> auth-secret str/trim not-empty)])


(defn machine-sort-key
  "Builds a stable sort key for machine ordering, preferring explicit ids when present."
  [machine]
  [(boolean (:id machine))
   (:id machine)
   (:ip machine)
   (:hostname machine)
   (:ssh-user machine)
   (:auth-type machine)])


(defn ensure-ssh-keypair!
  "Ensures that the target machine has an RSA SSH keypair ready for mesh setup."
  [machine]
  (log/info "Ensuring SSH keypair exists" {:machine (select-keys machine [:hostname :ip :ssh-user])})
  (let [result (exec-machine machine
                             (str "mkdir -p ~/.ssh "
                                  "&& chmod 700 ~/.ssh "
                                  "&& if [ ! -f ~/.ssh/id_rsa ]; then ssh-keygen -t rsa -b 4096 -N '' -f ~/.ssh/id_rsa >/dev/null 2>&1; fi "
                                  "&& chmod 600 ~/.ssh/id_rsa "
                                  "&& chmod 644 ~/.ssh/id_rsa.pub"))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Unable to create/read SSH keypair on machine."
                      {:machine machine :result result})))))


(defn known-host-identifiers
  "Returns the distinct hostname/IP values that should be added to known_hosts."
  [{:keys [hostname ip]}]
  (->> [hostname ip]
       (remove str/blank?)
       distinct))


(defn known-host-authorization-command
  "Builds the shell command that adds a host entry to ~/.ssh/known_hosts when needed."
  [host]
  (let [quoted-host (shell-quote host)]
    (format (str "mkdir -p ~/.ssh "
                 "&& chmod 700 ~/.ssh "
                 "&& touch ~/.ssh/known_hosts "
                 "&& chmod 600 ~/.ssh/known_hosts "
                 "&& (ssh-keygen -F %s -f ~/.ssh/known_hosts >/dev/null "
                 "|| ssh-keyscan -H %s >> ~/.ssh/known_hosts 2>/dev/null)")
            quoted-host
            quoted-host)))


(defn ensure-command-succeeded!
  "Throws an ex-info when a machine command exits with a non-zero status."
  [message machine context result]
  (when-not (zero? (:exit result))
    (throw (ex-info message
                    (assoc context
                           :machine machine
                           :result result)))))


(defn authorize-known-host!
  "Adds the target machine's hostname/IP SSH host keys to the source machine's known_hosts."
  [source-machine target-machine]
  (log/debug "Authorizing known host entries" {:source (select-keys source-machine [:hostname :ip :ssh-user])
                                               :target (select-keys target-machine [:hostname :ip :ssh-user])})
  (run! (fn [host]
          (let [result (exec-machine source-machine
                                     (known-host-authorization-command host))]
            (ensure-command-succeeded! "Unable to authorize SSH known host on machine."
                                       source-machine
                                       {:target target-machine}
                                       result)))
        (known-host-identifiers target-machine)))


(defn read-public-key!
  "Reads and returns the public key from the target machine's default SSH keypair."
  [machine]
  (log/debug "Reading public key from machine" {:machine (select-keys machine [:hostname :ip :ssh-user])})
  (let [result (exec-machine machine "cat ~/.ssh/id_rsa.pub")
        public-key (non-blank-string (:out result))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Unable to read machine public key."
                      {:machine machine :result result})))
    (when-not public-key
      (throw (ex-info "Machine public key is empty."
                      {:machine machine :result result})))
    public-key))


(defn authorize-public-key!
  "Appends a public key to the target machine's authorized_keys when it is not already present."
  [machine public-key]
  (log/debug "Authorizing public key on machine" {:machine (select-keys machine [:hostname :ip :ssh-user])})
  (let [quoted-key (shell-quote public-key)
        result (exec-machine machine
                             (str "mkdir -p ~/.ssh "
                                  "&& chmod 700 ~/.ssh "
                                  "&& touch ~/.ssh/authorized_keys "
                                  "&& chmod 600 ~/.ssh/authorized_keys "
                                  "&& (grep -qxF " quoted-key " ~/.ssh/authorized_keys "
                                  "|| echo " quoted-key " >> ~/.ssh/authorized_keys)"))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Unable to authorize SSH key on machine."
                      {:machine machine :result result})))))


(defn setup-passwordless-group-via-ssh!
  "Bootstraps passwordless SSH for one already-compatible group of machines."
  [machines]
  (log/info "Bootstrapping passwordless SSH for compatible group" {:machine-count (count machines)
                                                                   :machines (mapv #(select-keys % [:hostname :ip :ssh-user :auth-type]) machines)})
  (let [machine-keys (mapv (fn [machine]
                             (ensure-ssh-keypair! machine)
                             {:machine machine
                              :public-key (read-public-key! machine)})
                           machines)]
    (run! (fn [source-machine]
            (run! (fn [target-machine]
                    (authorize-known-host! source-machine target-machine))
                  machines))
          machines)
    (run! (fn [target-machine]
            (run! (fn [{:keys [public-key]}]
                    (authorize-public-key! target-machine public-key))
                  machine-keys))
          machines)
    (log/info "Finished passwordless SSH group bootstrap" {:machine-count (count machines)})
    :ok))


(defn setup-passwordless-group!
  "Validates and applies passwordless SSH setup for one grouped set of compatible machines."
  [machines]
  (let [{:keys [ssh-user auth-type]} (first machines)
        ips (mapv :ip machines)]
    (log/info "Evaluating passwordless SSH group" {:ssh-user ssh-user
                                                   :auth-type auth-type
                                                   :machine-ips ips})
    (cond
      (< (count machines) 2)
      (do
        (log/info "Skipping passwordless SSH group with fewer than two machines" {:machine-ips ips})
        {:applied false
         :skipped true
         :ssh-user ssh-user
         :auth-type auth-type
         :machine-ips ips
         :reason "Need at least two machines with matching credentials to configure passwordless SSH mesh."})

      (and (= "password" auth-type)
           (nil? (non-blank-string (:auth-secret (first machines)))))
      (do
        (log/info "Skipping passwordless SSH password-auth group without a password" {:machine-ips ips})
        {:applied false
         :skipped true
         :ssh-user ssh-user
         :auth-type auth-type
         :machine-ips ips
         :reason "Password auth requires auth-secret to be set for passwordless SSH setup."})

      (and (= "private-key" auth-type)
           (nil? (private-key-path (:auth-secret (first machines)))))
      (do
        (log/info "Skipping passwordless SSH private-key group with unreadable key" {:machine-ips ips})
        {:applied false
         :skipped true
         :ssh-user ssh-user
         :auth-type auth-type
         :machine-ips ips
         :reason "Private key auth requires auth-secret to point to a readable private key file."})

      :else
      (try
        (setup-passwordless-group-via-ssh! machines)
        (log/info "Applied passwordless SSH group" {:machine-ips ips})
        {:applied true
         :skipped false
         :ssh-user ssh-user
         :auth-type auth-type
         :machine-ips ips}
        (catch Exception ex
          (log/error ex "Passwordless SSH group bootstrap failed" {:machine-ips ips
                                                                   :ssh-user ssh-user
                                                                   :auth-type auth-type})
          {:applied false
           :skipped false
           :ssh-user ssh-user
           :auth-type auth-type
           :machine-ips ips
           :error (.getMessage ex)})))))


(defn setup-passwordless-mesh!
  "Groups machines by compatible SSH authentication details and bootstraps
  passwordless SSH within each eligible group."
  [machines]
  (log/info "Starting passwordless SSH mesh bootstrap" {:machine-count (count machines)})
  (let [remote-machines (->> machines
                             (remove localhost-machine?)
                             (sort-by machine-sort-key))
        grouped-machines (->> remote-machines
                              (group-by passwordless-group-key)
                              vals)
        results (mapv setup-passwordless-group! grouped-machines)
        summary {:eligible-machines (count remote-machines)
                 :groups (count grouped-machines)
                 :results results}]
    (log/info "Completed passwordless SSH mesh bootstrap" summary)
    summary))
