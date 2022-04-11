# Liberty Feature Explorer
Explore the relationships between features and artefacts in OpenLiberty.

This is a work in progress. Check back for updates to this README once it becomes a usable tool.


## Search strings
* `jms-2.0` should match only the exact feature short name, `jms-2.0`.
* `*jms*` should match every feature that includes `jms` in its short or full name.
* `jms-2.0/*` should match all features directly depended on by `jms-2.0`'s manifest.
* `jms-2.0/**` should match all features transitively depended on by `jms-2.0`
* `**/jms-2.0` 
* `a/**/b/**/c*` should match each of the following:
  * `a/b/c`
  * `a/x/b/y/c`
  * `a/b/c/d/cc`
* `a/**/b*/c*/**/d` -> `a`...`b*/c*`...`d`
  * `a/b/c/d`
  * `a/b/e/b/c/d`