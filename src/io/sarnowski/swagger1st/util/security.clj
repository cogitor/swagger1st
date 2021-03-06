(ns io.sarnowski.swagger1st.util.security
  (:require [ring.middleware.basic-authentication :refer [wrap-basic-authentication]]
            [clj-http.client :as client]
            [clojure.tools.logging :as log]
            [io.sarnowski.swagger1st.util.api :as api]
            [clj-time.core :as t])
  (:import (java.io IOException)))

;; functions that generate security handler

(defn- unauthorized
  "Send forbidden response."
  [reason]
  (log/warnf "ACCESS DENIED (unauthorized) because %s." reason)
  (api/error 401 "Unauthorized"))

(defn- forbidden
  "Send forbidden response."
  [reason]
  (log/warnf "ACCESS DENIED (forbidden) because %s." reason)
  (api/error 403 "Forbidden"))

(defn allow-all
  "Allows everything."
  []
  (fn [request definition requirements]
    request))

(defn basic-auth
  "Checks basic auth."
  [check-fn]
  (let [handler (wrap-basic-authentication (fn [request] request) check-fn)]
    (fn [request definition requirements]
      (handler request))))

(defn- extract-access-token
  "Extracts the bearer token from the Authorization header."
  [request]
  (if-let [authorization (get-in request [:headers "authorization"])]
    (when (.startsWith authorization "Bearer ")
      (.substring authorization (count "Bearer ")))))

(defn resolve-access-token
  "Checks with a tokeninfo endpoint for the token's validity and returns the session information if valid."
  [tokeninfo-url access-token]
  (try
    (let [response (client/get tokeninfo-url
                               {:query-params     {:access_token access-token}
                                :throw-exceptions false
                                :as               :json-string-keys})
          body (:body response)]
      (when (= 200 (:status response)) body))
    (catch IOException e
      (log/warn "could not get tokeninfo from" tokeninfo-url "because " (str e) "; rejecting token!")
      nil)))

(defn check-consented-scopes
  "Checks if every scope is mentioned in the 'scope' attribute of the token info."
  [tokeninfo scopes]
  (let [consented-scopes (set (get tokeninfo "scope"))]
    (every? consented-scopes scopes)))

(defn check-corresponding-attributes
  "Checks if every scope has a truethy attribute in the token info of the same name."
  [tokeninfo scopes]
  (let [scope-as-attribute-true? (fn [scope]
                                   (get tokeninfo scope))]
    (every? scope-as-attribute-true? scopes)))

(defn oauth-2.0
  "Checks OAuth 2.0 tokens.
   * config-fn takes one parameter for getting configuration values. Configuration values:
   ** :tokeninfo-url
   * check-scopes-fn takes tokeninfo and scopes data and returns if token is valid"
  [get-config-fn check-scopes-fn & {:keys [resolver-fn]
                                    :or {resolver-fn resolve-access-token}}]
  (fn [request definition requirements]
    ; get access token from request
    (if-let [access-token (extract-access-token request)]
      (let [tokeninfo-url (get-config-fn :tokeninfo-url)]
        (if tokeninfo-url
          ; check access token
          (if-let [tokeninfo (resolver-fn tokeninfo-url access-token)]
            ; check scopes
            (if (check-scopes-fn tokeninfo requirements)
              (assoc request :tokeninfo tokeninfo)
              (forbidden "scopes not granted"))
            (unauthorized "invalid access token"))
          (api/error 503 "token info misconfigured")))
      (unauthorized "no access token given"))))
