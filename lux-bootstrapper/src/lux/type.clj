;; This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
;; If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns lux.type
  (:refer-clojure :exclude [deref apply merge bound?])
  (:require [clojure.template :refer [do-template]]
            clojure.core.match
            clojure.core.match.array
            (lux [base :as & :refer [|do return* return assert! |let |case]])
            [lux.type.host :as &&host]))

(declare show-type
         type=)

;; [Utils]
(defn |list? [xs]
  (|case xs
    (&/$End)
    true

    (&/$Item x xs*)
    (|list? xs*)

    _
    false))

(def max-stack-size
  (->> 1
       (* 2)
       (* 2)
       (* 2)
       (* 2)
       (* 2)
       (* 2)
       (* 2)
       (* 2)))

(def empty-env &/$End)

(def I64 (&/$Named (&/T [&/prelude "I64"])
                   (&/$Universal empty-env
                                 (&/$Nominal "#I64" (&/|list (&/$Parameter 1))))))
(def Bit (&/$Named (&/T [&/prelude "Bit"]) (&/$Nominal "#Bit" &/$End)))
(def Nat (&/$Named (&/T [&/prelude "Nat"]) (&/$Nominal "#I64" (&/|list (&/$Nominal &&host/nat-data-tag &/$End)))))
(def Int (&/$Named (&/T [&/prelude "Int"]) (&/$Nominal "#I64" (&/|list (&/$Nominal &&host/int-data-tag &/$End)))))
(def Rev (&/$Named (&/T [&/prelude "Rev"]) (&/$Nominal "#I64" (&/|list (&/$Nominal &&host/rev-data-tag &/$End)))))
(def Frac (&/$Named (&/T [&/prelude "Frac"]) (&/$Nominal "#Frac" &/$End)))
(def Text (&/$Named (&/T [&/prelude "Text"]) (&/$Nominal "#Text" &/$End)))
(def Symbol (&/$Named (&/T [&/prelude "Symbol"]) (&/$Product Text Text)))

(def Array &&host/Array)

(def Nothing
  (&/$Named (&/T [&/prelude "Nothing"])
            (&/$Universal empty-env
                          (&/$Parameter 1))))

(def Any
  (&/$Named (&/T [&/prelude "Any"])
            (&/$Existential empty-env
                            (&/$Parameter 1))))

(def IO
  (&/$Named (&/T [(str &/prelude "/control/io") "IO"])
            (&/$Universal empty-env
                          (&/$Nominal (str &/prelude "/control/io.IO")
                                      (&/|list (&/$Parameter 1))))))

(def List
  (&/$Named (&/T [&/prelude "List"])
            (&/$Universal empty-env
                          (&/$Sum
                           ;; .End
                           Any
                           ;; .Item
                           (&/$Product (&/$Parameter 1)
                                       (&/$Apply (&/$Parameter 1)
                                                 (&/$Parameter 0)))))))

(def Maybe
  (&/$Named (&/T [&/prelude "Maybe"])
            (&/$Universal empty-env
                          (&/$Sum
                           ;; .None
                           Any
                           ;; .Some
                           (&/$Parameter 1))
                          )))

(def Type
  (&/$Named (&/T [&/prelude "Type"])
            (let [Type (&/$Apply (&/$Nominal "" &/$End) (&/$Parameter 0))
                  TypeList (&/$Apply Type List)
                  TypePair (&/$Product Type Type)]
              (&/$Apply (&/$Nominal "" &/$End)
                        (&/$Universal empty-env
                                      (&/$Sum
                                       ;; Nominal
                                       (&/$Product Text TypeList)
                                       (&/$Sum
                                        ;; Sum
                                        TypePair
                                        (&/$Sum
                                         ;; Product
                                         TypePair
                                         (&/$Sum
                                          ;; Function
                                          TypePair
                                          (&/$Sum
                                           ;; Parameter
                                           Nat
                                           (&/$Sum
                                            ;; Var
                                            Nat
                                            (&/$Sum
                                             ;; Ex
                                             Nat
                                             (&/$Sum
                                              ;; Universal
                                              (&/$Product TypeList Type)
                                              (&/$Sum
                                               ;; Existential
                                               (&/$Product TypeList Type)
                                               (&/$Sum
                                                ;; App
                                                TypePair
                                                ;; Named
                                                (&/$Product Symbol Type)))))))))))
                                      )))))

(def Macro
  (&/$Named (&/T [&/prelude "Macro"])
            (&/$Nominal "#Macro" &/$End)))

(def Tag
  (&/$Named (&/T [&/prelude "Tag"])
            (&/$Nominal "#Tag" &/$End)))

(def Slot
  (&/$Named (&/T [&/prelude "Slot"])
            (&/$Nominal "#Slot" &/$End)))

(defn bound? [id]
  (fn [state]
    (if-let [type (->> state (&/get$ &/$type-context) (&/get$ &/$var-bindings) (&/|get id))]
      (|case type
        (&/$Some type*)
        (return* state true)
        
        (&/$None)
        (return* state false))
      ((&/fail-with-loc (str "[Type Error] Unknown type-var: " id))
       state))))

(defn deref [id]
  (fn [state]
    (if-let [type* (->> state (&/get$ &/$type-context) (&/get$ &/$var-bindings) (&/|get id))]
      (|case type*
        (&/$Some type)
        (return* state type)
        
        (&/$None)
        ((&/fail-with-loc (str "[Type Error] Un-bound type-var: " id))
         state))
      ((&/fail-with-loc (str "[Type Error] Unknown type-var: " id))
       state))))

(defn deref+ [type]
  (|case type
    (&/$Var id)
    (deref id)

    _
    (&/fail-with-loc (str "[Type Error] Type is not a variable: " (show-type type)))
    ))

(defn set-var [id type]
  (fn [state]
    (if-let [tvar (->> state (&/get$ &/$type-context) (&/get$ &/$var-bindings) (&/|get id))]
      (|case tvar
        (&/$Some bound)
        (if (type= type bound)
          (return* state nil)
          ((&/fail-with-loc (str "[Type Error] Cannot re-bind type var: " id " | Current type: " (show-type bound)))
           state))
        
        (&/$None)
        (return* (&/update$ &/$type-context (fn [ts] (&/update$ &/$var-bindings #(&/|put id (&/$Some type) %)
                                                                ts))
                            state)
                 nil))
      ((&/fail-with-loc (str "[Type Error] Unknown type-var: " id " | " (->> state (&/get$ &/$type-context) (&/get$ &/$var-bindings) &/|length)))
       state))))

(defn reset-var [id type]
  (fn [state]
    (if-let [tvar (->> state (&/get$ &/$type-context) (&/get$ &/$var-bindings) (&/|get id))]
      (return* (&/update$ &/$type-context (fn [ts] (&/update$ &/$var-bindings #(&/|put id (&/$Some type) %)
                                                              ts))
                          state)
               nil)
      ((&/fail-with-loc (str "[Type Error] Unknown type-var: " id " | " (->> state (&/get$ &/$type-context) (&/get$ &/$var-bindings) &/|length)))
       state))))

(defn unset-var [id]
  (fn [state]
    (if-let [tvar (->> state (&/get$ &/$type-context) (&/get$ &/$var-bindings) (&/|get id))]
      (return* (&/update$ &/$type-context (fn [ts] (&/update$ &/$var-bindings #(&/|put id &/$None %)
                                                              ts))
                          state)
               nil)
      ((&/fail-with-loc (str "[Type Error] Unknown type-var: " id " | " (->> state (&/get$ &/$type-context) (&/get$ &/$var-bindings) &/|length)))
       state))))

;; [Exports]
;; Type vars
(def reset-mappings
  (fn [state]
    (return* (&/update$ &/$type-context #(->> %
                                              (&/set$ &/$var-counter 0)
                                              (&/set$ &/$var-bindings (&/|table)))
                        state)
             nil)))

(def create-var
  (fn [state]
    (let [id (->> state (&/get$ &/$type-context) (&/get$ &/$var-counter))]
      (return* (&/update$ &/$type-context #(->> %
                                                (&/update$ &/$var-counter inc)
                                                (&/update$ &/$var-bindings (fn [ms] (&/|put id &/$None ms))))
                          state)
               id))))

(def existential
  ;; (Lux Type)
  (fn [compiler]
    (return* (&/update$ &/$type-context
                        (fn [context]
                          (&/update$ &/$ex-counter inc context))
                        compiler)
             (->> compiler
                  (&/get$ &/$type-context)
                  (&/get$ &/$ex-counter)
                  &/$Opaque))))

(defn with-var [k]
  (|do [id create-var]
    (k (&/$Var id))))

(defn clean* [?tid type]
  (|case type
    (&/$Var ?id)
    (if (= ?tid ?id)
      (|do [? (bound? ?id)]
        (if ?
            (deref ?id)
          (return type)))
      (|do [? (bound? ?id)]
        (if ?
            (|do [=type (deref ?id)
                  ==type (clean* ?tid =type)]
              (|case ==type
                (&/$Var =id)
                (if (= ?tid =id)
                  (|do [_ (unset-var ?id)]
                    (return type))
                  (|do [_ (reset-var ?id ==type)]
                    (return type)))

                _
                (|do [_ (reset-var ?id ==type)]
                  (return ==type))))
          (return type)))
      )

    (&/$Nominal ?name ?params)
    (|do [=params (&/map% (partial clean* ?tid) ?params)]
      (return (&/$Nominal ?name =params)))
    
    (&/$Function ?arg ?return)
    (|do [=arg (clean* ?tid ?arg)
          =return (clean* ?tid ?return)]
      (return (&/$Function =arg =return)))

    (&/$Apply ?param ?lambda)
    (|do [=lambda (clean* ?tid ?lambda)
          =param (clean* ?tid ?param)]
      (return (&/$Apply =param =lambda)))

    (&/$Product ?left ?right)
    (|do [=left (clean* ?tid ?left)
          =right (clean* ?tid ?right)]
      (return (&/$Product =left =right)))
    
    (&/$Sum ?left ?right)
    (|do [=left (clean* ?tid ?left)
          =right (clean* ?tid ?right)]
      (return (&/$Sum =left =right)))

    (&/$Universal ?env ?body)
    (|do [=env (&/map% (partial clean* ?tid) ?env)
          body* (clean* ?tid ?body)] ;; TODO: DO NOT CLEAN THE BODY
      (return (&/$Universal =env body*)))

    (&/$Existential ?env ?body)
    (|do [=env (&/map% (partial clean* ?tid) ?env)
          body* (clean* ?tid ?body)] ;; TODO: DO NOT CLEAN THE BODY
      (return (&/$Existential =env body*)))

    _
    (return type)
    ))

(defn clean [tvar type]
  (|case tvar
    (&/$Var ?id)
    (clean* ?id type)
    
    _
    (&/fail-with-loc (str "[Type Error] Not type-var: " (show-type tvar)))))

(defn ^:private unravel-fun [type]
  (|case type
    (&/$Function ?in ?out)
    (|let [[??out ?args] (unravel-fun ?out)]
      (&/T [??out (&/$Item ?in ?args)]))

    _
    (&/T [type &/$End])))

(defn ^:private unravel-app
  ([fun-type tail]
   (|case fun-type
     (&/$Apply ?arg ?func)
     (unravel-app ?func (&/$Item ?arg tail))

     _
     (&/T [fun-type tail])))
  ([fun-type]
   (unravel-app fun-type &/$End)))

(do-template [<tag> <flatten> <at> <desc>]
  (do (defn <flatten>
        "(-> Type (List Type))"
        [type]
        (|case type
          (<tag> left right)
          (&/$Item left (<flatten> right))

          _
          (&/|list type)))

    (defn <at>
      "(-> Int Type (Lux Type))"
      [tag type]
      (|case type
        (&/$Named ?name ?type)
        (<at> tag ?type)
        
        (<tag> ?left ?right)
        (|case (&/T [tag ?right])
          [0 _]                (return ?left)
          [1 (<tag> ?left* _)] (return ?left*)
          [1 _]                (return ?right)
          [_ (<tag> _ _)]      (<at> (dec tag) ?right)
          _                    (&/fail-with-loc (str "[Type Error] " <desc> " lacks member: " tag " | " (show-type type))))

        _
        (&/fail-with-loc (str "[Type Error] Type is not a " <desc> ": " (show-type type))))))

  &/$Sum  flatten-sum  sum-at  "Sum"
  &/$Product flatten-prod prod-at "Product"
  )

(do-template [<name> <ctor> <unit>]
  (defn <name>
    "(-> (List Type) Type)"
    [types]
    (|case (&/|reverse types)
      (&/$Item last prevs)
      (&/fold (fn [right left] (<ctor> left right)) last prevs)

      (&/$End)
      <unit>))

  Variant$ &/$Sum  Nothing
  Tuple$   &/$Product Any
  )

(defn show-type [^objects type]
  (|case type
    (&/$Nominal name params)
    (|case params
      (&/$End)
      (str "(Nominal " (pr-str name) ")")

      _
      (str "(Nominal " (pr-str name) " " (->> params (&/|map show-type) (&/|interpose " ") (&/fold str "")) ")"))

    (&/$Product _)
    (str "[" (->> (flatten-prod type) (&/|map show-type) (&/|interpose " ") (&/fold str "")) "]")

    (&/$Sum _)
    (str "(| " (->> (flatten-sum type) (&/|map show-type) (&/|interpose " ") (&/fold str "")) ")")
    
    (&/$Function input output)
    (|let [[?out ?ins] (unravel-fun type)]
      (str "(-> " (->> ?ins (&/|map show-type) (&/|interpose " ") (&/fold str "")) " " (show-type ?out) ")"))

    (&/$Var id)
    (str "-" id)

    (&/$Opaque ?id)
    (str "+" ?id)

    (&/$Parameter idx)
    (str idx)

    (&/$Apply _ _)
    (|let [[?call-fun ?call-args] (unravel-app type)]
      (str "(" (show-type ?call-fun) " " (->> ?call-args (&/|map show-type) (&/|interpose " ") (&/fold str "")) ")"))
    
    (&/$Universal ?env ?body)
    (str "(All " "{" (->> ?env (&/|map show-type) (&/|interpose " ") (&/fold str "")) "} "
         (show-type ?body) ")")

    (&/$Existential ?env ?body)
    (str "(Ex " "{" (->> ?env (&/|map show-type) (&/|interpose " ") (&/fold str "")) "} "
         (show-type ?body) ")")
    
    (&/$Named ?name ?type)
    (&/ident->text ?name)

    _
    (assert false (prn-str 'show-type (&/adt->text type)))))

(defn type= [x y]
  (or (clojure.lang.Util/identical x y)
      (let [output (|case [x y]
                     [(&/$Named [?xmodule ?xname] ?xtype) (&/$Named [?ymodule ?yname] ?ytype)]
                     (and (= ?xmodule ?ymodule)
                          (= ?xname ?yname))

                     [(&/$Nominal xname xparams) (&/$Nominal yname yparams)]
                     (and (.equals ^Object xname yname)
                          (= (&/|length xparams) (&/|length yparams))
                          (&/fold2 #(and %1 (type= %2 %3)) true xparams yparams))

                     [(&/$Product xL xR) (&/$Product yL yR)]
                     (and (type= xL yL)
                          (type= xR yR))

                     [(&/$Sum xL xR) (&/$Sum yL yR)]
                     (and (type= xL yL)
                          (type= xR yR))

                     [(&/$Function xinput xoutput) (&/$Function yinput youtput)]
                     (and (type= xinput yinput)
                          (type= xoutput youtput))

                     [(&/$Var xid) (&/$Var yid)]
                     (= xid yid)

                     [(&/$Parameter xidx) (&/$Parameter yidx)]
                     (= xidx yidx)

                     [(&/$Opaque xid) (&/$Opaque yid)]
                     (= xid yid)

                     [(&/$Apply xparam xlambda) (&/$Apply yparam ylambda)]
                     (and (type= xparam yparam) (type= xlambda ylambda))
                     
                     [(&/$Universal xenv xbody) (&/$Universal yenv ybody)]
                     (type= xbody ybody)

                     [(&/$Named ?xname ?xtype) _]
                     (type= ?xtype y)

                     [_ (&/$Named ?yname ?ytype)]
                     (type= x ?ytype)
                     
                     [_ _]
                     false
                     )]
        output)))

(defn ^:private fp-get [k fixpoints]
  (|let [[e a] k]
    (|case fixpoints
      (&/$End)
      &/$None

      (&/$Item [[e* a*] v*] fixpoints*)
      (if (and (type= e e*)
               (type= a a*))
        (&/$Some v*)
        (fp-get k fixpoints*))
      )))

(defn ^:private fp-put [k v fixpoints]
  (&/$Item (&/T [k v]) fixpoints))

(defn show-type+ [type]
  (|case type
    (&/$Var ?id)
    (fn [state]
      (|case ((deref ?id) state)
        (&/$Right state* bound)
        (return* state (str (show-type type) " = " (show-type bound)))

        (&/$Left _)
        (return* state (show-type type))))

    _
    (return (show-type type))))

(defn ^:private check-error [err expected actual]
  (|do [=expected (show-type+ expected)
        =actual (show-type+ actual)]
    (&/fail-with-loc (str (if (= "" err) err (str err "\n"))
                          "[Type Checker Error]\n"
                          "Expected: " =expected "\n\n"
                          "  Actual: " =actual
                          "\n"))))

(defn beta-reduce [env type]
  (|case type
    (&/$Nominal ?name ?params)
    (&/$Nominal ?name (&/|map (partial beta-reduce env) ?params))

    (&/$Sum ?left ?right)
    (&/$Sum (beta-reduce env ?left) (beta-reduce env ?right))

    (&/$Product ?left ?right)
    (&/$Product (beta-reduce env ?left) (beta-reduce env ?right))

    (&/$Apply ?type-arg ?type-fn)
    (&/$Apply (beta-reduce env ?type-arg) (beta-reduce env ?type-fn))
    
    (&/$Universal ?local-env ?local-def)
    (|case ?local-env
      (&/$End)
      (&/$Universal env ?local-def)

      _
      type)

    (&/$Existential ?local-env ?local-def)
    (|case ?local-env
      (&/$End)
      (&/$Existential env ?local-def)

      _
      type)

    (&/$Function ?input ?output)
    (&/$Function (beta-reduce env ?input) (beta-reduce env ?output))

    (&/$Parameter ?idx)
    (|case (&/|at ?idx env)
      (&/$Some parameter)
      (beta-reduce env parameter)

      _
      (assert false (str "[Type Error] Unknown var: " ?idx " | " (&/->seq (&/|map show-type env)))))

    _
    type
    ))

(defn apply-type [type-fn param]
  (|case type-fn
    (&/$Universal local-env local-def)
    (return (beta-reduce (->> local-env
                              (&/$Item param)
                              (&/$Item type-fn))
                         local-def))

    (&/$Existential local-env local-def)
    (return (beta-reduce (->> local-env
                              (&/$Item param)
                              (&/$Item type-fn))
                         local-def))

    (&/$Apply A F)
    (|do [type-fn* (apply-type F A)]
      (apply-type type-fn* param))

    (&/$Named ?name ?type)
    (apply-type ?type param)

    ;; TODO: This one must go...
    (&/$Opaque id)
    (return (&/$Apply param type-fn))

    (&/$Var id)
    (|do [=type-fun (deref id)]
      (apply-type =type-fun param))
    
    _
    (&/fail-with-loc (str "[Type System] Not a type function:\n" (show-type type-fn) "\n"
                          "for arg: " (show-type param)))))

(def ^:private init-fixpoints &/$End)

(defn ^:private check* [fixpoints invariant?? expected actual]
  (if (clojure.lang.Util/identical expected actual)
    (return fixpoints)
    (&/with-attempt
      (|case [expected actual]
        [(&/$Var ?eid) (&/$Var ?aid)]
        (if (= ?eid ?aid)
          (return fixpoints)
          (|do [ebound (fn [state]
                         (|case ((deref ?eid) state)
                           (&/$Right state* ebound)
                           (return* state* (&/$Some ebound))

                           (&/$Left _)
                           (return* state &/$None)))
                abound (fn [state]
                         (|case ((deref ?aid) state)
                           (&/$Right state* abound)
                           (return* state* (&/$Some abound))

                           (&/$Left _)
                           (return* state &/$None)))]
            (|case [ebound abound]
              [(&/$None _) (&/$None _)]
              (|do [_ (set-var ?eid actual)]
                (return fixpoints))
              
              [(&/$Some etype) (&/$None _)]
              (check* fixpoints invariant?? etype actual)

              [(&/$None _) (&/$Some atype)]
              (check* fixpoints invariant?? expected atype)

              [(&/$Some etype) (&/$Some atype)]
              (check* fixpoints invariant?? etype atype))))
        
        [(&/$Var ?id) _]
        (fn [state]
          (|case ((set-var ?id actual) state)
            (&/$Right state* _)
            (return* state* fixpoints)

            (&/$Left _)
            ((|do [bound (deref ?id)]
               (check* fixpoints invariant?? bound actual))
             state)))
        
        [_ (&/$Var ?id)]
        (fn [state]
          (|case ((set-var ?id expected) state)
            (&/$Right state* _)
            (return* state* fixpoints)

            (&/$Left _)
            ((|do [bound (deref ?id)]
               (check* fixpoints invariant?? expected bound))
             state)))

        [(&/$Apply eA (&/$Opaque eid)) (&/$Apply aA (&/$Opaque aid))]
        (if (= eid aid)
          (check* fixpoints invariant?? eA aA)
          (check-error "" expected actual))

        [(&/$Apply A1 (&/$Var ?id)) (&/$Apply A2 F2)]
        (fn [state]
          (|case ((|do [F1 (deref ?id)]
                    (check* fixpoints invariant?? (&/$Apply A1 F1) actual))
                  state)
            (&/$Right state* output)
            (return* state* output)

            (&/$Left _)
            (|case F2
              (&/$Universal (&/$Item _) _)
              ((|do [actual* (apply-type F2 A2)]
                 (check* fixpoints invariant?? expected actual*))
               state)

              (&/$Opaque _)
              ((|do [fixpoints* (check* fixpoints invariant?? (&/$Var ?id) F2)]
                 (check* fixpoints* invariant?? A1 A2))
               state)

              _
              ((|do [fixpoints* (check* fixpoints invariant?? (&/$Var ?id) F2)
                     e* (apply-type F2 A1)
                     a* (apply-type F2 A2)]
                 (check* fixpoints* invariant?? e* a*))
               state))))
        
        [(&/$Apply A1 F1) (&/$Apply A2 (&/$Var ?id))]
        (fn [state]
          (|case ((|do [F2 (deref ?id)]
                    (check* fixpoints invariant?? expected (&/$Apply A2 F2)))
                  state)
            (&/$Right state* output)
            (return* state* output)

            (&/$Left _)
            ((|do [fixpoints* (check* fixpoints invariant?? F1 (&/$Var ?id))
                   e* (apply-type F1 A1)
                   a* (apply-type F1 A2)]
               (check* fixpoints* invariant?? e* a*))
             state)))
        
        [(&/$Apply A F) _]
        (let [fp-pair (&/T [expected actual])
              _ (when (> (&/|length fixpoints) max-stack-size)
                  (&/|log! (print-str 'FIXPOINTS (->> (&/|keys fixpoints)
                                                      (&/|map (fn [pair]
                                                                (|let [[e a] pair]
                                                                  (str (show-type e) ":+:"
                                                                       (show-type a)))))
                                                      (&/|interpose "\n\n")
                                                      (&/fold str ""))))
                  (assert false (prn-str 'check* '[(&/$Apply A F) _] (&/|length fixpoints) (show-type expected) (show-type actual))))]
          (|case (fp-get fp-pair fixpoints)
            (&/$Some ?)
            (if ?
                (return fixpoints)
              (check-error "" expected actual))

            (&/$None)
            (|do [expected* (apply-type F A)]
              (check* (fp-put fp-pair true fixpoints) invariant?? expected* actual))))

        [_ (&/$Apply A (&/$Opaque aid))]
        (check-error "" expected actual)

        [_ (&/$Apply A F)]
        (|do [actual* (apply-type F A)]
          (check* fixpoints invariant?? expected actual*))

        [(&/$Universal _) _]
        (|do [$arg existential
              expected* (apply-type expected $arg)]
          (check* fixpoints invariant?? expected* actual))

        [_ (&/$Universal _)]
        (with-var
          (fn [$arg]
            (|do [actual* (apply-type actual $arg)
                  =output (check* fixpoints invariant?? expected actual*)
                  _ (clean $arg expected)]
              (return =output))))

        [(&/$Existential e!env e!def) _]
        (with-var
          (fn [$arg]
            (|do [expected* (apply-type expected $arg)
                  =output (check* fixpoints invariant?? expected* actual)
                  _ (clean $arg actual)]
              (return =output))))

        [_ (&/$Existential a!env a!def)]
        (|do [$arg existential
              actual* (apply-type actual $arg)]
          (check* fixpoints invariant?? expected actual*))

        [(&/$Nominal e!data) (&/$Nominal a!data)]
        (|do [? &/jvm?]
          (if ?
              (|do [class-loader &/loader]
                (&&host/check-host-types (partial check* fixpoints true)
                                         check-error
                                         fixpoints
                                         existential
                                         class-loader
                                         invariant??
                                         e!data
                                         a!data))
            (|let [[e!name e!params] e!data
                   [a!name a!params] a!data]
              (if (and (= e!name a!name)
                       (= (&/|length e!params) (&/|length a!params)))
                (|do [_ (&/map2% (partial check* fixpoints true) e!params a!params)]
                  (return fixpoints))
                (check-error "" expected actual)))))

        [(&/$Function eI eO) (&/$Function aI aO)]
        (|do [fixpoints* (check* fixpoints invariant?? aI eI)]
          (check* fixpoints* invariant?? eO aO))

        [(&/$Product eL eR) (&/$Product aL aR)]
        (|do [fixpoints* (check* fixpoints invariant?? eL aL)]
          (check* fixpoints* invariant?? eR aR))

        [(&/$Sum eL eR) (&/$Sum aL aR)]
        (|do [fixpoints* (check* fixpoints invariant?? eL aL)]
          (check* fixpoints* invariant?? eR aR))

        [(&/$Opaque e!id) (&/$Opaque a!id)]
        (if (= e!id a!id)
          (return fixpoints)
          (check-error "" expected actual))

        [(&/$Named _ ?etype) _]
        (check* fixpoints invariant?? ?etype actual)

        [_ (&/$Named _ ?atype)]
        (check* fixpoints invariant?? expected ?atype)

        [_ _]
        (&/fail ""))
      (fn [err]
        (check-error err expected actual)))))

(defn check [expected actual]
  (|do [_ (check* init-fixpoints false expected actual)]
    (return nil)))

(defn actual-type
  "(-> Type (Lux Type))"
  [type]
  (|case type
    (&/$Apply ?param ?all)
    (|do [type* (apply-type ?all ?param)]
      (actual-type type*))

    (&/$Var id)
    (|do [=type (deref id)]
      (actual-type =type))

    (&/$Named ?name ?type)
    (actual-type ?type)
    
    _
    (return type)
    ))

(defn type-name
  "(-> Type (Lux Symbol))"
  [type]
  (|case type
    (&/$Named name _)
    (return name)
    
    _
    (&/fail-with-loc (str "[Type Error] Type is not named: " (show-type type)))
    ))

(defn unknown?
  "(-> Type (Lux Bit))"
  [type]
  (|case type
    (&/$Var id)
    (|do [? (bound? id)]
      (return (not ?)))

    _
    (return false)))

(defn resolve-type
  "(-> Type (Lux Type))"
  [type]
  (|case type
    (&/$Var id)
    (|do [? (bound? id)]
      (if ?
          (deref id)
        (return type)))

    _
    (return type)))

(defn tuple-types-for
  "(-> Int Type [Int (List Type)])"
  [size-members type]
  (|let [?member-types (flatten-prod type)
         size-types (&/|length ?member-types)]
    (if (>= size-types size-members)
      (&/T [size-members (&/|++ (&/|take (dec size-members) ?member-types)
                                (&/|list (|case (->> ?member-types (&/|drop (dec size-members)) (&/|reverse))
                                           (&/$Item last prevs)
                                           (&/fold (fn [right left] (&/$Product left right))
                                                   last prevs))))])
      (&/T [size-types ?member-types])
      )))

(do-template [<name> <zero> <plus>]
  (defn <name> [types]
    (|case (&/|reverse types)
      (&/$End)
      <zero>

      (&/$Item type (&/$End))
      type

      (&/$Item last prevs)
      (&/fold (fn [r l] (<plus> l r)) last prevs)))

  fold-prod Any &/$Product
  fold-sum  Nothing &/$Sum
  )

(def create-var+
  (|do [id create-var]
    (return (&/$Var id))))

(defn ^:private push-app [inf-type inf-var]
  (|case inf-type
    (&/$Apply inf-var* inf-type*)
    (&/$Apply inf-var* (push-app inf-type* inf-var))

    _
    (&/$Apply inf-var inf-type)))

(defn ^:private push-name [name inf-type]
  (|case inf-type
    (&/$Apply inf-var* inf-type*)
    (&/$Apply inf-var* (push-name name inf-type*))

    _
    (&/$Named name inf-type)))

(defn ^:private push-univq [env inf-type]
  (|case inf-type
    (&/$Apply inf-var* inf-type*)
    (&/$Apply inf-var* (push-univq env inf-type*))

    _
    (&/$Universal env inf-type)))

(defn instantiate-inference [type]
  (|case type
    (&/$Named ?name ?type)
    (|do [output (instantiate-inference ?type)]
      (return (push-name ?name output)))

    (&/$Universal _aenv _abody)
    (|do [inf-var create-var
          output (instantiate-inference _abody)]
      (return (push-univq _aenv (push-app output (&/$Var inf-var)))))

    _
    (return type)))

(defn normal
  "(-> Type Type)"
  [it]
  (|case it
    (&/$Named _ ?it)
    (normal ?it)

    (&/$Nominal ?name ?parameters)
    (|do [=parameters (&/map% normal ?parameters)]
      (return (&/$Nominal ?name =parameters)))

    (&/$Apply ?parameter ?abstraction)
    (|do [reification (apply-type ?abstraction ?parameter)]
      (normal reification))

    (&/$Var id)
    (|do [referenced (deref id)]
      (normal referenced))

    _
    (return it)))
