# passwordless-ssh

[![CircleCI](https://circleci.com/gh/punit-naik/passwordless-ssh/tree/master.svg?style=svg)](https://circleci.com/gh/punit-naik/passwordless-ssh/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.punit-naik/passwordless-ssh.svg)](https://clojars.org/org.clojars.punit-naik/passwordless-ssh)

`passwordless-ssh` is a small Clojure library for bootstrapping passwordless SSH across machines that can already be reached over SSH.

## Coordinates

```clojure
[org.clojars.punit-naik/passwordless-ssh "1.0.0"]
```

## What It Does

- Connects to remote machines over SSH using either password auth or a private key file.
- Ensures each machine has an SSH keypair.
- Adds peer host keys to `known_hosts`.
- Adds each machine's public key to every other machine's `authorized_keys`.
- Groups machines by compatible SSH authentication details and applies setup only within each group.

## Machine Shape

Each machine is a map like:

```clojure
{:ip "10.0.0.11"
 :ssh-user "ubuntu"
 :auth-type "password"
 :auth-secret "cluster-pass"}
```

Optional fields:

- `:id` for stable ordering only
- `:hostname` as an extra `known_hosts` identifier in addition to `:ip`

Required fields:

- `:ip`
- `:ssh-user`
- `:auth-type`
- `:auth-secret` for supported auth modes

Supported `:auth-type` values:

- `"password"` where `:auth-secret` is the SSH password
- `"private-key"` where `:auth-secret` is a readable private key path on the machine running this library

## Usage

```clojure
(ns example
  (:require [passwordless-ssh.core :as pssh]))

(def machines
  [{:ip "10.0.0.11" :ssh-user "ubuntu" :auth-type "password" :auth-secret "cluster-pass"}
   {:ip "10.0.0.12" :ssh-user "ubuntu" :auth-type "password" :auth-secret "cluster-pass"}
   {:ip "10.0.0.13" :ssh-user "ubuntu" :auth-type "password" :auth-secret "cluster-pass"}])

(pssh/setup-passwordless-mesh! machines)
;; => {:eligible-machines 3
;;     :groups 1
;;     :results [{:applied true
;;                :skipped false
;;                :ssh-user "ubuntu"
;;                :auth-type "password"
;;                :machine-ips ["10.0.0.11" "10.0.0.12" "10.0.0.13"]}]}
```

If you want to operate on a single already-compatible group directly:

```clojure
(pssh/setup-passwordless-group-via-ssh! machines)
```

## Notes

- Machines with `:ip` of `localhost` or `127.0.0.1` are excluded from mesh setup.
- Machines are grouped by `:ssh-user`, `:auth-type`, and normalized `:auth-secret`.
- Passwordless setup is skipped for groups smaller than two machines.
- `:hostname` is optional and only improves `known_hosts` coverage when hostname-based SSH is used later.
- The library sets SSH `StrictHostKeyChecking=no` for bootstrap connections.

## API Docs

Generated API documentation is available at:

- [https://punit-naik.github.io/passwordless-ssh](https://punit-naik.github.io/passwordless-ssh)

## Code Coverage

Generated coverage reports are available at:

- [https://punit-naik.github.io/passwordless-ssh/coverage](https://punit-naik.github.io/passwordless-ssh/coverage)

## Test

```bash
lein test
```

Optional Docker integration test:

```bash
lein test-cluster-simulation
```

Keep the Docker integration cluster running after the test for manual inspection:

```bash
PASSWORDLESS_SSH_DOCKER_KEEP_CLUSTER=true lein test-cluster-simulation
```

And then destroy the Docker integration cluster:

```bash
docker compose -f test-resources/docker-compose.yml down -v --remove-orphans
```

License
Copyright © 2026 [Punit Naik](https://www.github.com/punit-naik)

Distributed under the Eclipse Public License version 2.0.
