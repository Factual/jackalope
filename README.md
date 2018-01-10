# Jackalope

An opinionated approach to spry software development using github.

## Project build

Jackalope is a Clojure project built with [Leiningen](http://leiningen.org/) (lein). Once you have Java and Leiningen installed and you've cloned the project, you can run the project tests like so...

```bash
lein test
```

There are integration tests which require a local `github-test.edn` file in order to pass (see the test's docs).

... and you can build a runnable jar like so:
```bash
lein uberjar
```

Once the jar is successfully built you can run it from your command line. E.g.:
```
java -jar target/jackalope.jar --help
```

For added convenience you can create a script as a shortcut and put it in your path. E.g., create this file named jack, make it exectutable, and put it in `~/bin`:
```bash
java -jar ~/workspace/jackalope/target/jackalope.jar "$@"
```

## Github and ZenHub access

Jackalope helps manage Github issues based on ZenHub boards. It therefore requires credentials for a Github account that has access to the corresponding repository, and credential for a ZenHub API account that has access to the corresponding ZenHub boards. 

When using Jackalope from the command line, point to your credentials settings with the `--conf` option. Example credential file contents:

```clojure
{:user "a_user_or_org"
 :repo "a_repo"
 :github-token "a_long_token_string_generated_via_your_github_acct"}
```

## Command Line Usage

This section illustrates Jackalope's basic CLI.

__check-sprint__ Checks the repo for open issues requesting either a sprint start or a sprint stop. Open issues are processed unless you specify preview mode, which will provide a brief description of actions to take, but won't perform those actions.

Examples:
```
jackalope check-sprint --conf github-prod.edn
jackalope check-sprint --conf github-prod.edn --preview
```

Jackalope will look for sprint start and stop requests by searching open issues and matching against the issue title, like so:
* A title "start sprint" is interpreted as a request to start a sprint for that issue's milestone
* A title "stop sprint" is interpreted as a request to stop a sprint for that issue's milestone

When preview is not specified, Jackalope will run sprint starts and stops, taking these steps:

For a *sprint start*:
* Composes a sprint plan based on data from GitHub & ZenHub
* Moves 'no' items to next milestone
* Writes a comment to the issue, showing the plan as a table
* Sets the Milestone's description to contain the plan data
* Closes the issue

For a *sprint stop*:
* Sweeps unclosed issues to next milestone
* Publishes retrospective info to the issue as a set of comments / tables
* Closes the issue

When preview is specified, Jackalope will provide some basic info on the console about the actions that would be performed.

Example preview output, for a sprint start:
```
Checking sprint for ava-v
Issue #532
Milestone #3, 'Milestone C'
Plan:===
 The plan looks like:

__Yes:__

Number|Title
---|---
#309|This one will get done
#310|This one doesn't get done

__Maybe:__

Number|Title
---|---
#448|Some Random Complaint
#449|Some Random Question 
===
```

Example preview output, for a sprint stop:
```
Checking sprint for ava-v
------ Sprint stop preview ------
Issue #501
Milestone #3, 'Milestone C'
{:number 449, :action :assign-milestone, :ms-num 4}
{:number 310, :action :assign-milestone, :ms-num 4}
{:number 449, :action :unmaybe}
{:number 448, :action :unmaybe}
```

## REPL based examples

This section illustrates use of Jackalope on the REPL.

### Connect

You establish a connection by calling the `connect!` function in the `core` namespace. You can supply no arguments, in which case it will attempt to read credentials from `github-prod.edn` in the current working directory. Or you can provide a file path as an argument, in which case it'll attempt to read credentials from that file. E.g.:

```clojure
(connect! "/Users/aaroncrow/workspace/jackalope/github-test.edn")
```

When the credentials file is found and read, `connect!` will return `true` (but this does not guarantee that the credentials are correct).


### Example REPLE session -- generate a sprint start description

The following example assumes:
* you have a valid configuration file for testing, `github-test.edn`, in the current working directory
* you've just finished planning a sprint
* you've opened an issue titled 'start sprint', assigned to the milestone of your sprint

```bash
$ lein repl
jackalope.main=> (in-ns 'jackalope.core)
jackalope.core=> (connect! "github-test.edn")
jackalope.core=> (def ISSUE (first (:start (find-work "ava-v"))))
jackalope.core=> (def START (sprint-start* ISSUE))
jackalope.core=> (keys START)
jackalope.core=> (count (:plan START))
jackalope.core=> (:plan START)
```

