(ns jepsen.postgres.db
  "Database setup and automation.

  Runs one of several locally built Postgres variants, which must already be
  installed on every node under the prefix given in `variants` (we build them
  once and copy /opt/pg around; this namespace does not compile Postgres)."
  (:require [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c :refer [|]]
                    [core :as jepsen]
                    [db :as db]
                    [random :as rand]
                    [util :as util :refer [meh random-nonempty-subset]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.debian :as debian]
            [jepsen.postgres [client :as sc]]
            [slingshot.slingshot :refer [try+ throw+]]))

(def user
  "The OS user which will run postgres."
  "postgres")

(def variants
  "Postgres builds we know how to run, by --variant name.

    :prefix     where the build is installed on each node
    :orioledb?  whether to load OrioleDB and make it the default table AM"
  {:vanilla {:prefix "/opt/pg/vanilla", :orioledb? false}
   :oriole  {:prefix "/opt/pg/oriole",  :orioledb? true}})

(def base-dir     "/var/lib/jepsen-pg")
(def data-dir     (str base-dir "/data"))
(def log-file     (str base-dir "/postgresql.log"))
(def cluster-name "jepsen")

(defn variant
  "The variant map for this test."
  [test]
  (or (variants (:variant test))
      (throw+ {:type ::unknown-variant, :variant (:variant test)})))

(defn bin
  "Path to a binary of this test's Postgres build, e.g. (bin test :pg_ctl)."
  [test cmd]
  (str (:prefix (variant test)) "/bin/" (name cmd)))

(defn expected-am
  "The table access method new tables should get in this variant."
  [test]
  (if (:orioledb? (variant test)) "orioledb" "heap"))

(defn process-patterns
  "Patterns (pgrep -f regexes) we use to target various postgres processes for
  killing. The postmaster keeps its command line; other processes are titled
  `postgres: <cluster_name>: <role>`."
  [test]
  [(str "^" (bin test :postgres) " -D")
   (str "^postgres: " cluster-name ": checkpointer")
   (str "^postgres: " cluster-name ": background writer")
   (str "^postgres: " cluster-name ": walwriter")
   (str "^postgres: " cluster-name ": autovacuum launcher")])

(def all-processes-pattern
  "Matches every postgres process of any variant: postmasters and children."
  (str "^/opt/pg/[a-z]+/bin/postgres|^postgres: " cluster-name))

(defn pg-conf
  "Extra postgresql.conf settings for this test."
  [test]
  (str (slurp (io/resource "jepsen.conf"))
       "\n"
       "cluster_name = '" cluster-name "'\n"
       (when (:orioledb? (variant test))
         "shared_preload_libraries = 'orioledb'\n")))

(defn psql!
  "Runs SQL statements in a single psql session as the postgres user, over the
  local socket. Returns unaligned, tuples-only output."
  [test & statements]
  (c/sudo user
          (apply c/exec (bin test :psql) :-X :-A :-t :-q
                 :-d "postgres" :-v "ON_ERROR_STOP=1"
                 (mapcat (fn [s] [:-c s]) statements))))

(defn running?
  "Is the postmaster running?"
  [test]
  (try+ (c/sudo user (c/exec (bin test :pg_ctl) :-D data-dir :status))
        true
        (catch [:type :jepsen.control/nonzero-exit] _ false)))

(defn start-pg!
  "Starts the postmaster unless it's already running. Right after a kill -9,
  surviving backends can briefly hold the old shared memory segment, so we
  retry a few times."
  [test]
  (if (running? test)
    :already-running
    (loop [tries 5]
      (let [res (try+ (c/sudo user
                              (c/exec (bin test :pg_ctl) :-D data-dir
                                      :-l log-file :-w :-t 120 :start))
                      :started
                      (catch [:type :jepsen.control/nonzero-exit] e
                        (if (pos? (dec tries)) ::retry (throw+ e))))]
        (if (= ::retry res)
          (do (warn "pg_ctl start failed; retrying")
              (Thread/sleep 2000)
              (recur (dec tries)))
          res)))))

(defn kill-all!
  "Unpauses and kills every postgres process on the node."
  []
  (c/su (cu/grepkill! :CONT all-processes-pattern)
        (cu/grepkill! all-processes-pattern)))

(defn check-am!
  "Creates a probe table in a fresh session and checks that it lands in the
  access method this variant is supposed to use. Guards against silently
  testing heap when we meant to test OrioleDB. The text column makes the
  probe fail the same way the workload's tables would if the database's
  default collation is one the engine can't use."
  [test]
  (let [am (str/trim (psql! test
                            "CREATE TABLE jepsen_am_probe (id int, v text)"
                            (str "SELECT a.amname FROM pg_class c"
                                 " JOIN pg_am a ON a.oid = c.relam"
                                 " WHERE c.relname = 'jepsen_am_probe'")
                            "DROP TABLE jepsen_am_probe"))]
    (when-not (= (expected-am test) am)
      (throw+ {:type     ::wrong-access-method
               :variant  (:variant test)
               :expected (expected-am test)
               :actual   am}))
    (info "New tables use access method" am)))

(defn db
  "A single-node Postgres running one of our locally built variants."
  [opts]
  (reify db/DB
    (setup! [_ test node]
      (let [v (variant test)]
        (info "Setting up Postgres variant" (:variant test) v)
        (c/su
          (when-not (cu/exists? (bin test :postgres))
            (throw+ {:type    ::missing-build
                     :node    node
                     :variant (:variant test)
                     :binary  (bin test :postgres)}))
          (cu/ensure-user! user)
          (kill-all!)
          (c/exec :rm :-rf base-dir)
          (c/exec :install :-d :-o user :-g user base-dir))

        (info "Initializing data directory")
        (c/sudo user
                ; OrioleDB tables only support C, POSIX, ICU and builtin
                ; collations, so a libc C.UTF-8 default (inherited from the
                ; node's locale) breaks every text column. Use C everywhere,
                ; so all variants are compared under the same collation.
                (c/exec (bin test :initdb) :-D data-dir :-A :trust
                        :--locale=C :--encoding=UTF8)
                (cu/write-file! (pg-conf test) (str data-dir "/jepsen.conf"))
                (c/exec :echo "include 'jepsen.conf'"
                        (c/lit ">>") (str data-dir "/postgresql.conf"))
                (cu/write-file! (slurp (io/resource "pg_hba.conf"))
                                (str data-dir "/pg_hba.conf")))

        (info "Starting Postgres")
        (start-pg! test)
        (cu/await-tcp-port 5432)

        (when (:orioledb? v)
          (info "Enabling OrioleDB")
          (psql! test
                 "CREATE EXTENSION orioledb"
                 "ALTER DATABASE postgres SET default_table_access_method = 'orioledb'"))
        ; A new psql session, so the database default above applies.
        (check-am! test)
        (info "Postgres" (str/trim (c/exec (bin test :postgres) :--version))
              "ready")))

    (teardown! [_ test node]
      (info "Tearing down Postgres")
      (kill-all!)
      (c/su (c/exec :rm :-rf base-dir)))

    db/LogFiles
    (log-files [_ test node]
      {log-file "postgresql.log"})

    db/Primary
    (setup-primary! [db test node])
    (primaries [db test]
      ; Everyone's a winner! Really, there should only be one node here,
      ; so... it's kinda trivial.
      (:nodes test))

    db/Process
    (start! [db test node]
      (start-pg! test))

    (kill! [db test node]
      (c/su
        (doseq [pattern (rand/shuffle (process-patterns test))]
          (info "Killing" pattern "-" (cu/grepkill! pattern)))))

    db/Pause
    (pause! [db test node]
      (c/su
        (doseq [pattern (rand/shuffle (process-patterns test))]
          (info "Pausing" pattern "-" (cu/grepkill! :STOP pattern)))))

    (resume! [db test node]
      (c/su
        (doseq [pattern (rand/shuffle (process-patterns test))]
          (info "Resuming" pattern "-" (cu/grepkill! :CONT pattern)))))))
