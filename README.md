sbt-typescript
================

An SBT plugin to run the [TypeScript](http://www.typescriptlang.org/) compiler inside of SBT.

To use this plugin download and build it yourself.

This plugin is compatible with [sbt-web](https://github.com/sbt/sbt-web).

Usage
=====

Simply run the `tsc` command to invoke the TypeScript compiler. No surprises :

```
> tsc
[info] Version 1.0.1.0
[info] Syntax:   tsc [options] [file ..]
[info]
[info] Examples: tsc hello.ts
[info]           tsc --out foo.js foo.ts
[info]           tsc @args.txt
[info]
[info] Options:
[info]   -d, --declaration             Generates corresponding .d.ts file.
[info]   -h, --help                    Print this message.
[info]   --mapRoot LOCATION            Specifies the location where debugger should locate map files instead of generated locations.
[info]   -m KIND, --module KIND        Specify module code generation: 'commonjs' or 'amd'
[info]   --noImplicitAny               Warn on expressions and declarations with an implied 'any' type.
[info]   --out FILE                    Concatenate and emit output to single file.
[info]   --outDir DIRECTORY            Redirect output structure to the directory.
[info]   --removeComments              Do not emit comments to output.
[info]   --sourcemap                   Generates corresponding .map file.
[info]   --sourceRoot LOCATION         Specifies the location where debugger should locate TypeScript files instead of source locations.
[info]   -t VERSION, --target VERSION  Specify ECMAScript target version: 'ES3' (default), or 'ES5'
[info]   -v, --version                 Print the compiler's version: 1.0.1.0
[info]   -w, --watch                   Watch input files.
[info]   @<file>                       Insert command line options and files from a file.
```

Use with sbt-web
================

To use this plugin with sbt-web start by enabling SbtWeb in your build.sbt file:

    lazy val root = (project in file(".")).enablePlugins(SbtWeb)

Once configured, any `*.ts` files placed in `src/main/assets` will be compiled to JavaScript code in `target/web/public`.

Supported settings:

* `sourceMap` When set, generates sourceMap files. Defaults to `false`.

  `TypeScriptKeys.sourceMap := true`

* `targetES5` When set, target ECMAScript 5. Defaults to `false` (target ECMAScript 3).

  `TypeScriptKeys.targetES5 := true`

* `noImplicitAny` When set, warn on expressions and declarations with an implied 'any' type. Default to `false`.

  `TypeScriptKeys.noImplicitAny := true`

* `removeComments` When set, do not emit comments to output. Defaults to `false`.

  `TypeScriptKeys.targetES5 := true`

* `moduleType` Specify module code generation: Can be 'Commonjs' or 'Amd'. Defaults to `Amd`.

  `TypeScriptKeys.moduleType in Assets := TypeScriptKeys.ModuleType.Amd`
  `TypeScriptKeys.moduleType in TestAssets := TypeScriptKeys.ModuleType.CommonJs`

The plugin is built on top of [JavaScript Engine](https://github.com/typesafehub/js-engine) which supports different JavaScript runtimes.

KNOW ISSUES:
* Contrary to other sbt-web plugins this one does not (yet) run incrementally. A local Node instance is advised for performance.
* Still young and not extensively tested.