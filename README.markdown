JGit-Describe
=============
The [DMDirc Project](http://www.dmdirc.com/) uses `git describe` to version all of its jars, which includes a large number of plugins which are built at build time as well as the main client itself. Due to changes to ANT some time ago which killed the performance of the `<exec />` task that was previosuly used for this we had a need for a proper ant task to do this job.

Based off erussell/jgit-describe (in turn based off mdonoughe/jgit-describe) this is a slightly customised version of the original jgit-describe that supports passing a sub dir to the task such that the version of the last commit that affected that subdir is used, rather than the version of the whole repo. (This was required for the DMDirc plugin versioning.)

As well as subdir support, this version can also handle .git-file based git repos (eg, submodules mainly).

This uses the wonderful [jgit](https://github.com/eclipse/jgit) library for any actual interaction with git, and both the jgit-describe.jar and a jgit.jar will be required to use this.

(In future I will have the build script merge the required jgit files into the a separate jgit-describe-full.jar or so so that only a single jar is required.)


Building
--------
If this is a fresh or newly updated checkout, run `git submodule update --init` to get the latest jgit submodule and then run `ant` (or `ant jar` to actually build the jar file.

CI
--

JGit-Describe Uses Travis-CI for Continuous Integration, the status of which is shown below.

All pull requests must pass CI before being accepted.

[![Build Status](https://travis-ci.org/ShaneMcC/jgit-describe.png?branch=master)](https://travis-ci.org/ShaneMcC/jgit-describe)

Usage
-----
Add a taskdef like the following to your build.xml.

    <taskdef name="git-describe" classname="org.mdonoughe.JGitDescribeTask" classpath="lib/jgit-describe.jar:lib/jgit.jar"/>



To use the new git-describe task to populate a property with a string describing the current HEAD revision:

    <git-describe dir=".git" property="describe"/>

`dir` is the path to the .git directory
`property` is the name of the property to populate



To use the new git-describe task to populate a property with a string describing the last revision to affect the "src/co/uk/shanemcc/foobar" sub directory do:

    <git-describe dir=".git" property="describe" subdir="src/co/uk/shanemcc/foobar" />

`subdir` is a path relative to the git repository root (NOT the current directory). This should also always use a forward slash (/) not a backslash (\) even on windows. (Although jgit-describe will automatically handle this if required). It is also possible to pass multiple paths in using a semi-colon (;) eg:

    <git-describe dir=".git" property="describe" subdir="src/co/uk/shanemcc/foobar;src/co/uk/shanemcc/baz" />

This will use the most recent commit that affected either repository.
