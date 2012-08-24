(ns pallet.crate.vblob
  "Crates for cloudfoundry's vblob installation and configuration.

https://github.com/cloudfoundry/vblob"
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.action :as action]
   [pallet.action.user :as user]
   [pallet.parameter :as parameter]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string])
  (:use
   [cheshire.core :only [generate-string]]
   [pallet.action :only [with-action-options]]
   [pallet.action.exec-script :only [exec-checked-script]]
   [pallet.action.package
    :only [package package-source package-manager* install-deb]]
   [pallet.action.package.jpackage :only [add-jpackage]]
   [pallet.action.remote-directory :only [remote-directory]]
   [pallet.action.remote-file :only [remote-file]]
   [pallet.common.context :only [throw-map]]
   [pallet.compute :only [os-hierarchy]]
   [pallet.core :only [server-spec]]
   [pallet.crate.forever :only [forever-service]]
   [pallet.parameter :only [assoc-target-settings get-target-settings]]
   [pallet.phase :only [phase-fn]]
   [pallet.thread-expr :only [when-> apply-map->]]
   [pallet.utils :only [apply-map]]
   [pallet.version-dispatch
    :only [defmulti-version-crate defmulti-version defmulti-os-crate
           multi-version-session-method multi-version-method
           multi-os-session-method]]
   [pallet.versions :only [version-string as-version-vector]]))

;;; ## vblob install

(def ^:dynamic *download-url*
  "https://github.com/cloudfoundry/vblob/tarball/master")

(def ^:dynamic *vblob-defaults*
  {:user "vblob"
   :home "/usr/local/vblob"
   :version "1.0-SNAPSHOT"
   :port 9981
   :drivers [{:fs-1 {:type "fs" :option {}}}]
   :current_driver "fs-1"
   :logtype "winston"
   :logfile "/usr/local/vblob/log.txt"
   :auth "s3"
   :debug true
   :account_api false
   :keyID "MvndHwA4e6dgaGV23L94"
   :secretID "A50GS9tj2DLXRln4rf1K+A/CSjmAbBGw0H5yul6s"})

;;; Based on supplied settings, decide which install strategy we are using
;;; for vblob.

(defmulti-version-crate vblob-version-settings [version session settings])

(multi-version-session-method
    vblob-version-settings {:os :linux}
    [os os-version version session settings]
  (cond
    (:strategy settings) settings
    (:download settings) (assoc settings :strategy :download)
    :else (assoc settings
            :strategy :download
            :download {:url *download-url*})))

;;; ## Settings
(defn- settings-map
  "Dispatch to either openjdk or oracle settings"
  [session settings]
  (vblob-version-settings
   session
   (as-version-vector (string/replace (:version settings) "-SNAPSHOT" ""))
   (merge *vblob-defaults* settings)))

(defn vblob-settings
  "Capture settings for vblob

:user
:home
:version
:port

:download"
  [session {:keys [version user home port download instance-id]
            :or {version "1.0-SNAPSHOT"}
            :as settings}]
  (let [settings (settings-map session (merge {:version version} settings))]
    (assoc-target-settings session :vblob instance-id settings)))

;;; # Install

;;; Dispatch to install strategy
(defmulti install-method (fn [session settings] (:strategy settings)))
(defmethod install-method :download
  [session {:keys [user download home] :as settings}]
  (->
   session
   (user/user user :system true :home home :shell "/bin/false")
   (apply-map-> remote-directory home :owner user download)))

(defn install-vblob
  "Install vblob. By default will install as a war into jetty."
  [session & {:keys [instance-id]}]
  (let [settings (get-target-settings
                  session :vblob instance-id ::no-settings)]
    (logging/debugf "install-vblob settings %s" settings)
    (if (= settings ::no-settings)
      (throw-map
       "Attempt to install vblob without specifying settings"
       {:message "Attempt to install vblob without specifying settings"
        :type :invalid-operation})
      (install-method session settings))))

;;; # Configure
(defn configure-vblob
  [session & {:keys [instance-id]}]
  (let [{:keys [home user] :as settings}
        (get-target-settings session :vblob instance-id ::no-settings)
        settings (-> settings
                     (dissoc :version)
                     (update-in [:port] str))]
    (remote-file
     session (str home "/config.json")
     :content (generate-string settings {:pretty true})
     :user user :literal true)))

;;; # Forever based service
(defn vblob-forever
  [session & {:keys [action max instance-id]
              :as options}]
  (let [{:keys [home user] :as settings}
        (get-target-settings session :vblob instance-id ::no-settings)]
    (apply-map forever-service session "server.js"
               (assoc options :dir home))))

;;; # Server spec
(defn vblob
  "Returns a service-spec for installing vblob."
  [settings]
  (server-spec
   :phase {:settings (phase-fn (vblob-settings settings))
           :configure (phase-fn
                        (install-vblob)
                        (configure-vblob))}))
