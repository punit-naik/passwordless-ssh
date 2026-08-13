(defproject org.clojars.punit-naik/passwordless-ssh "1.0.1"
  :description "Standalone passwordless SSH setup for groups of machines that can authenticate over SSH"
  :url "https://github.com/punit-naik/passwordless-ssh"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [com.github.mwiede/jsch "2.28.6"]
                 [com.taoensso/timbre "6.8.0"]]
  :test-selectors {:default (complement :docker-integration)
                   :docker-integration :docker-integration
                   :all (constantly true)}
  :aliases {"test-cluster-simulation" ["test" ":docker-integration"]}
  :profiles {:test {:resource-paths ["test-resources"]}
             :dev {:dependencies [[mvxcvi/cljstyle "0.17.642"]]}})
