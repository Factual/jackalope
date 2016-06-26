# Jackalope

An opinionated approach to spry software development using github.

## Connecting

Jackalope helps manage Github issues and therefore requires credentials for a Github account that has access to the corresponding repository.

You establish a connection by calling the `github!` function in the `core` namespace. You can supply no arguments, in which case it will attempt to read credentials from `github-prod.edn` in the current working directory. Or you can provide a file path as an argument, in which case it'll attempt to read credentials from that file. E.g.:

```clojure
(github! "/Users/aaroncrow/workspace/jackalope/github-test.edn")
```

When the credentials file is found and read, `github!` will return `true` (but this does not guarantee that the credentials are correct).

Example credentials file:
```clojure
{:auth "some_user:some_password"
 :user "dirtyvagabond"
 :repo "some_repo"}
```

In the above example, we are asking Jackalope to login as `some_user` for the purpose of managing issues in a Github repo named `some_repo`, owned by dirtyvagabond. Presumably, dirtyvagabond has already granted collaboration privileges to `some_user`. Note that `some_user` will be the user indicated by Github as the user doing the ticket management (editing labels, milestone assignments, etc.)

## REPL based examples

This section illustrates use of Jackalope on the REPL.

### Import and finalize a plan

This workflow reads a sprint plan and edits the corresponding issues appropriately. For each issue in the plan:
* Assigns the current release milestone if the issue is planned as "yes" or "maybe"
* Assigns the next (future) release milestone if the issue is planned as "no"
* Adds the 'maybe' label if the issue is planned as a "maybe"

This workflow requires:
* a JSON file of the plan. The JSON file is expected to be the format imported from Zenhub
* 2 milestones in github: one milestone that represents the sprint for the plan, and one milestone that represents the next (future) sprint

__Example REPL session:__

The following example assumes:
* you have a valid `github-prod.edn` file in the current working directory
* you've just finished planning a sprint, which you've named "1.2.3"
* the sprint  for 1.2.3 is represented by milestone id 1 in Github
* you have "1.2.3.plan.json" file in the current working directory.
* the milestone for the future release (e.g., 1.2.4) is milestone id 2 in Github

```bash
$ lein repl
user=> (load "jackalope/core")
user=> (in-ns 'jackalope.core)
jackalope.core=> (github!)
jackalope.core=> (def PLAN (pst/import-plan-from-json "1.2.3"))
jackalope.core=> (doseq [d PLAN] (println d)) ; preview the plan
jackalope.core=> (def MS-CURR 1) ; milestone id for current plan
jackalope.core=> (def MS-NEXT 2) ; milestone id for next (future) plan
jackalope.core=> (def RES (plan! PLAN MS-CURR MS-NEXT))
jackalope.core=> (doseq [e (:edits RES)] (println e)) ; review changes
```

Example output from the plan preview:
```
{:number 255, :do? :no}
{:number 256, :do? :maybe}
{:number 257, :do? :yes}
```

Example output from the changes review:
```
{:number 255, :milestone 2}
{:number 256, :milestone 1, :label+ :maybe}
{:number 257, :milestone 1}
```

### Sweep a milestone

This workflow sweeps a specified milestone:
* clears 'maybe' labels from the issues in the current milestone
* rolls forward incomplete (non closed) issues from the current milestone to the next milestone

This workflow requires:
* 2 milestones in github: one milestone that is the current milestone (the milestone to be swept), and one milestone that represents the next (future) sprint (the milestone to roll forward non-closed tickets into).

__Example REPL session:__

The following example assumes:
* you have a valid `github-prod.edn` file in the current working directory
* there is a milestone with id 1, which is out of time and must be swept
* there is a milestone with id 2, which represents the next (planning) milestone

```bash
$ lein repl
user=> (load "jackalope/core")
user=> (in-ns 'jackalope.core)
jackalope.core=> (github!)
jackalope.core=> (def ACTS (sweep-milestone 1 2))
jackalope.core=> (doseq [a ACTS] (println a)) ; preview the sweep
jackalope.core=> (sweep! ACTS)
```

### Generate a retrospective report

This workflow generates a retrospective report from a given plan and milestone. The report shows basic outcomes, such as which planned items were completed and which were not. a specified milestone:

This workflow requires:
* a milestone in github (the recently completed milestone for which to generate a retrospective report)
* the EDN file that represents the plan for the recent release

__Example REPL session:__

The following example assumes:
* you have a valid `github-prod.edn` file in the current working directory
* there is a milestone with id 1, from which you wish to see a retrospective
* you have "1.2.3.plan.edn" file in the current working directory (probably generated from the _"Import and finalize a plan"_ workflow, above)

```bash
$ lein repl
user=> (load "jackalope/core")
user=> (in-ns 'jackalope.core)
jackalope.core=> (github!)
jackalope.core=> (generate-retrospective-report 1 "1.2.3")
```

This will result in a retrospective report that compares the plan for release "1.2.3" with the state of the issues in milestone id 1. The report will be formatted as HTML and saved in the file, `./1.2.3.retrospective.html`
