(ns mineralwoolmfg.registry-test
  (:require [clojure.test :refer [deftest is]]
            [mineralwoolmfg.registry :as r]))

;; ----------------------------- equipment-verified? / equipment-registered? / equipment-ready? -----------------------------

(deftest equipment-is-verified-when-flagged
  (is (true? (r/equipment-verified? {:id "e1" :verified? true}))))

(deftest equipment-is-not-verified-when-false-or-missing
  (is (false? (r/equipment-verified? {:id "e1" :verified? false})))
  (is (false? (r/equipment-verified? {:id "e1"}))))

(deftest equipment-is-registered-when-flagged
  (is (true? (r/equipment-registered? {:registered? true}))))

(deftest equipment-is-not-registered-when-false-or-missing
  (is (false? (r/equipment-registered? {:registered? false})))
  (is (false? (r/equipment-registered? {}))))

(deftest equipment-ready-requires-both
  (is (true? (r/equipment-ready? {:verified? true :registered? true})))
  (is (false? (r/equipment-ready? {:verified? true :registered? false})))
  (is (false? (r/equipment-ready? {:verified? false :registered? true})))
  (is (false? (r/equipment-ready? {}))))

;; ----------------------------- batch-verified? / batch-registered? / batch-ready? -----------------------------

(deftest batch-is-verified-when-flagged
  (is (true? (r/batch-verified? {:id "b1" :verified? true}))))

(deftest batch-is-not-verified-when-false-or-missing
  (is (false? (r/batch-verified? {:id "b1" :verified? false})))
  (is (false? (r/batch-verified? {:id "b1"}))))

(deftest batch-is-registered-when-flagged
  (is (true? (r/batch-registered? {:registered? true}))))

(deftest batch-is-not-registered-when-false-or-missing
  (is (false? (r/batch-registered? {:registered? false})))
  (is (false? (r/batch-registered? {}))))

(deftest batch-ready-requires-both
  (is (true? (r/batch-ready? {:verified? true :registered? true})))
  (is (false? (r/batch-ready? {:verified? true :registered? false})))
  (is (false? (r/batch-ready? {:verified? false :registered? true})))
  (is (false? (r/batch-ready? {}))))

;; ----------------------------- shipment-weight-exceeded? -----------------------------

(deftest small-shipment-within-weight-does-not-exceed
  (is (false? (r/shipment-weight-exceeded?
               {:weight-kg 40000.0 :shipped-weight-kg 8000.0} 5000.0))))

(deftest shipment-that-pushes-past-weight-exceeds
  (is (true? (r/shipment-weight-exceeded?
              {:weight-kg 6000.0 :shipped-weight-kg 5600.0} 1000.0))))

(deftest shipment-exactly-at-weight-does-not-exceed
  (is (false? (r/shipment-weight-exceeded?
               {:weight-kg 6000.0 :shipped-weight-kg 5600.0} 400.0))
      "exactly at weight is not over, only strictly beyond"))

(deftest missing-weight-is-not-flagged-exceeded
  (is (false? (r/shipment-weight-exceeded? {} 100.0)))
  (is (false? (r/shipment-weight-exceeded? {:weight-kg 800.0} nil))))

;; ----------------------------- product-type-valid? -----------------------------

(deftest known-product-types-are-valid
  (doseq [g [:batt-insulation :roll-insulation :loose-fill-insulation
             :rigid-board-insulation :pipe-section-insulation :duct-wrap-insulation
             :acoustic-panel :fire-stop-wrap]]
    (is (r/product-type-valid? g))))

(deftest fabricated-product-type-is-invalid
  (is (not (r/product-type-valid? :unobtainium-insulation)))
  (is (not (r/product-type-valid? nil))))

;; ----------------------------- thermal-conductivity-valid? -----------------------------

(deftest typical-thermal-conductivity-is-valid
  (is (r/thermal-conductivity-valid? 0.035))
  (is (r/thermal-conductivity-valid? 0.020))
  (is (r/thermal-conductivity-valid? 0.045))
  (is (r/thermal-conductivity-valid? 0.060)))

(deftest negative-thermal-conductivity-is-invalid
  (is (not (r/thermal-conductivity-valid? -0.01))))

(deftest excessive-thermal-conductivity-is-invalid
  (is (not (r/thermal-conductivity-valid? 9.0)))
  (is (not (r/thermal-conductivity-valid? 0.0601))))

(deftest non-numeric-or-missing-thermal-conductivity-is-invalid
  (is (not (r/thermal-conductivity-valid? nil)))
  (is (not (r/thermal-conductivity-valid? "0.035"))))

;; ----------------------------- density-valid? -----------------------------

(deftest typical-density-is-valid
  (is (r/density-valid? 16.0))
  (is (r/density-valid? 8.0))
  (is (r/density-valid? 120.0))
  (is (r/density-valid? 250.0)))

(deftest negative-density-is-invalid
  (is (not (r/density-valid? -1.0))))

(deftest excessive-density-is-invalid
  (is (not (r/density-valid? 9999.0)))
  (is (not (r/density-valid? 250.01))))

(deftest non-numeric-or-missing-density-is-invalid
  (is (not (r/density-valid? nil)))
  (is (not (r/density-valid? "16.0"))))

;; ----------------------------- register-maintenance -----------------------------

(deftest maintenance-is-a-draft-not-a-real-actuation
  (let [result (r/register-maintenance "mnt-1" "forming-line-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest maintenance-assigns-maintenance-number
  (let [result (r/register-maintenance "mnt-1" "forming-line-001" 7)]
    (is (= (get result "maintenance_number") "MNT-000007"))
    (is (= (get-in result ["record" "maintenance_id"]) "mnt-1"))
    (is (= (get-in result ["record" "equipment_id"]) "forming-line-001"))
    (is (= (get-in result ["record" "kind"]) "maintenance-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest maintenance-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "" "forming-line-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "mnt-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-maintenance "mnt-1" "forming-line-001" -1))))

;; ----------------------------- register-shipment -----------------------------

(deftest shipment-is-a-draft-not-a-real-dispatch
  (let [result (r/register-shipment "ship-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest shipment-assigns-shipment-number
  (let [result (r/register-shipment "ship-1" 7)]
    (is (= (get result "shipment_number") "SHP-000007"))
    (is (= (get-in result ["record" "shipment_id"]) "ship-1"))
    (is (= (get-in result ["record" "kind"]) "shipment-coordination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest shipment-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-shipment "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-shipment "ship-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-maintenance "mnt-1" "forming-line-001" 0)
        hist (r/append [] c1)
        c2 (r/register-maintenance "mnt-2" "forming-line-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "MNT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "MNT-000001" (get-in hist2 [1 "record_id"])))))
