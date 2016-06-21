(ns jackalope.integration-test
  (:require [clojure.test :refer :all]
            [jackalope.core :refer :all]
            [jackalope.github :as github]
            [jackalope.issues :as issues]))

; These integration tests require the following things in order to run properly:
; 
; 1) A github-test.edn on the classpath, with access to a valid test account.
;    The file should look like:
;    {:auth 'login:pwd' 
;     :user 'MyOrg'
;     :repo 'myrepo'}
;
; 2) An active repo named as specified by the :repo setting in (1)
;
; 3) A milestone with id 1 in the active specified repo from (1) and clearance
;    to create and manage tickets in that repo and assigned to that milestone


(def CONF "github-test.edn")
(def MILESTONE-A 1)
(def MILESTONE-B 2)


;
; Convenience functions
;

(defn rand-title []
  (str "Some Random " 
       (rand-nth ["Bug" "Feature" "Question" "Complaint"]) " "
       (rand-int 1000)))

(defn create-test-issue [ms-num]
  (github/create-issue (github-conn) (rand-title) ms-num))

(defn create-test-issues
  "Creates n new issues, assigned to the milestone ms-num.
   Returns a collection of the issues."
  [n ms-num]
  (doall
   (repeatedly n #(create-test-issue ms-num))))

(defn a-plan
  "Given three issues (a, b, and c) returns a minimal plan where:
  * issue a is :yes
  * issue b is :no
  * issue c is :maybe"
  [a b c]
  [{:number (:number a) :do? :yes}
   {:number (:number b) :do? :no}
   {:number (:number c) :do? :maybe}])

(defn unmaybed-act? [acts inum]
  (some #(and (= :unmaybe (:action %))
              (= inum (:number %))) acts))

(defn milestoned-act? [acts inum ms-num]
  (some #(and (= :assign-milestone (:action %))
              (= ms-num (:ms-num %))
              (= inum (:number %))) acts))



;
; Integration tests
;

(deftest test-finalize-plan
  "Creates new issues in milestone A then runs an example plan on them.
   Current milestone is set as milestone A, next milestone is set as milestone B.

   Creates these issues for testing:
   (A) an issue planned as 'yes'; should stay in milestone A
   (B) an issue planned as 'no'; should be moved to milestone B
   (C) an issue planned as 'maybe'; should stay in milestone A and get maybe label"
  []
  (github! CONF)

  ;; Create issues A, B, and C and create a plan for them
  (let [[a b c] (create-test-issues 3 MILESTONE-A)
        plan (a-plan a b c)]

    ;; the main event
    (plan! plan MILESTONE-A MILESTONE-B)
    
    ;; Lookup all three issues and verify state
    (let [[a b c]
          (github/fetch-issues-by-nums (github-conn) (map :number [a b c]))]

      ;; Issue A -- still in milestone A, no 'maybe' label
      (is (= MILESTONE-A (get-in a [:milestone :number])))
      (is (not (issues/has-maybe-label? a)))

      ;; Issue B -- moved to milestone B, no 'maybe' label
      (is (= MILESTONE-B (get-in b [:milestone :number])))
      (is (not (issues/has-maybe-label? b)))

      ;; Issue C -- still in milestone A, with 'maybe' label
      (is (= MILESTONE-A (get-in c [:milestone :number])))
      (is (issues/has-maybe-label? c)))))

(deftest test-milestone-sweep
  "Creates new issues in milestone A and tests they they are treated properly 
   when 'swept' to milestone B.

   Creates these issues for testing:
   (A) an issue that gets a maybe label and remains open. 
       should be unmaybe'd and swept forward
   (B) an issue with no label and remains open.
       should get no maybe change and should get swept forward
   (C) an issue that gets closed.
       should get no maybe change and should not get swept forward"
  []
  (github! CONF)

  ;; Create issues A, B, and C. Then get actions
  (let [[a b c] (create-test-issues 3 MILESTONE-A)
        ;; add maybe label to issue a
        ra (first (github/add-a-label (github-conn) (:number a) :maybe))
        _  (assert (= "maybe" (:name ra)) (str "(:name ra) unexpectedly: " (:name ra)))
        ;; close issue c
        rc (github/edit-issue (github-conn) {:number (:number c)
                                             :state :closed})
        _  (assert (:closed_at rc))
        acts (sweep-milestone MILESTONE-A MILESTONE-B)]

    ;; -- Verify actions --
    ;; Issue A -- unmaybe'd and swept forward
    (comment
      (is (unmaybed-act? acts (:number a)))
      (is (milestoned-act? acts (:number a) MILESTONE-B))
      ;; Issue B -- no label change; swept forward
      (is (not (unmaybed-act? acts (:number b))))
      (is (milestoned-act? acts (:number b) MILESTONE-B))
      ;; Issue C -- no actions
      (is (not (some #(= (:number c) (:number %)) acts))))
    
    ;; The main event
    (sweep! acts)

    ;; Lookup all three issues and verify state
    (let [[a b c]
          (github/fetch-issues-by-nums (github-conn) (map :number [a b c]))]

      ;; Issue A -- unmaybe'd and swept forward
      (is (not (issues/has-maybe-label? a)))
      (is (= MILESTONE-B (get-in a [:milestone :number])))

      ;; Issue B -- swept forward
      (is (= MILESTONE-B (get-in b [:milestone :number])))

      ;; Issue C -- not swept forward
      (is (= MILESTONE-A (get-in c [:milestone :number]))))))


;
; Conveniences for manual REPL-based testing
;

(defn setup-a-plan
  "Creates new issues in milestone A then runs an example plan on them.
   Current milestone is set as milestone A, next milestone is set as milestone B.

   Creates these issues for testing:
   (A) an issue planned as 'yes'; should stay in milestone A
   (B) an issue planned as 'no'; should be moved to milestone B
   (C) an issue planned as 'maybe'; should stay in milestone A and get maybe label

   Returns:
   {:issues [the 3 test issues, A, B, C]
    :plan   [the plan structure]}"
  []
  (github! CONF)

  ;; Create issues A, B, and C and create a plan for them
  (let [[a b c] (create-test-issues 3 MILESTONE-A)
        plan (a-plan a b c)]
    {:issues [a b c]
     :plan plan}))





;;
;; Firefly is an American space western science fiction drama
;;
