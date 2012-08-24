(ns pallet.crate.vblob-test
  (:use
   [org.jclouds.blobstore2 :only [blobstore containers]]
   [pallet.action :only [def-clj-action]]
   [pallet.action.exec-script :only [exec-checked-script]]
   [pallet.action.package :only [install-deb package]]
   [pallet.crate.node-js :only [install-nodejs nodejs-settings]]
   [pallet.crate.forever :only [install-forever forever-settings]]
   [pallet.node :only [primary-ip]]
   [pallet.parameter :only [get-target-settings]]
   [pallet.parameter-test :only [settings-test]]
   [pallet.session :only [nodes-in-group]]
   clojure.test
   pallet.test-utils)
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.blobstore :as blobstore]
   [pallet.build-actions :as build-actions]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.vblob :as vblob]
   [pallet.crate.network-service :as network-service]
   [pallet.enlive :as enlive]
   [pallet.live-test :as live-test]
   [pallet.parameter :as parameter]
   [pallet.parameter-test :as parameter-test]
   [pallet.phase :as phase]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (vblob/vblob-settings {})
       (vblob/install-vblob)
       (vblob/configure-vblob))))

(def settings-map {})

(def vblob-unsupported
  [])

(def node-deb
  "https://raw.github.com/cinderella/deploy/master/debs/nodejs-0.6.10_amd64.deb")

;; (defn install-forever
;;   [session & {:keys [version] :or {version "0.9.2"}}]
;;   (->
;;    session
;;    (package "libssl0.9.8")
;;    (exec-checked-script
;;     "Install forever"
;;     (npm install (str "forever@" ~version) -g --quiet -y))))

(def-clj-action verify-vblob
  [session group-name & {:keys [instance-id]}]
  (let [{:keys [home user s3-port keyID secretID] :as settings}
        (get-target-settings session :vblob instance-id ::no-settings)
        node (first (nodes-in-group session group-name))
        endpoint (format "http://%s:%s/" (primary-ip node) s3-port)
        _ (logging/debugf "Testing with %s %s %s" keyID secretID endpoint)
        bs (blobstore "aws-s3" keyID secretID :endpoint endpoint)]
    (is bs "Blobstore contactable")
    (is (containers bs) "Blobstore containers listable")
    (logging/infof "Blobstore containers %s" (vec (containers bs)))
    session))

(deftest live-test
  (live-test/test-for
   [image (live-test/exclude-images (live-test/images) vblob-unsupported)]
   (live-test/test-nodes
    [compute node-map node-types]
    {:vblob
     {:image image
      :count 1
      :phases {:bootstrap (phase/phase-fn
                           (automated-admin-user/automated-admin-user))
               :settings (phase/phase-fn
                          (nodejs-settings
                           {:deb
                            {:url node-deb
                             :md5 "597250b48364b4ed7ab929fb6a8410b8"}})
                          (forever-settings {:version "0.9.2"})
                          (vblob/vblob-settings settings-map))
               :configure (fn [session]
                            (->
                             session
                             (install-nodejs)
                             (install-forever)
                             (vblob/install-vblob)
                             (vblob/configure-vblob)
                             (vblob/vblob-forever :action :start :max 1)))
               :verify (phase/phase-fn
                        (verify-vblob :vblob))}}}
    (core/lift (:vblob node-types) :phase :verify :compute compute))))
