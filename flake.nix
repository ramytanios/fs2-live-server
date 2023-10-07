{
  description = "Purely functional live server";

  inputs.devshell.url = "github:numtide/devshell";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.flake-compat = {
    url = "github:edolstra/flake-compat";
    flake = false;
  };

  outputs = { self, flake-utils, devshell, nixpkgs, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;

          overlays = [ devshell.overlays.default ];
        };

        jdk = pkgs.jdk19_headless;
        graal-jdk = pkgs.graalvm-ce;
        sbt = pkgs.sbt.override { jre = jdk; };
        metals = pkgs.metals.override { jre = jdk; };
        scala-cli = pkgs.scala-cli.override { jre = jdk; };
        node = pkgs.nodejs;

        build-packages = [
          jdk
          scala-cli
          pkgs.clang
          pkgs.coreutils
          pkgs.llvmPackages.libcxxabi
          pkgs.openssl
          pkgs.s2n-tls
          pkgs.which
          pkgs.zlib
        ];

        # fixed-output derivation: to nix'ify scala-cli,
        # we must hash the coursier caches created during the build
        coursier-cache = pkgs.stdenv.mkDerivation {
          name = "coursier-cache";
          src = ./;

          buildInputs = build-packages;

          SCALA_CLI_HOME = "./scala-cli-home";
          COURSIER_CACHE = "./coursier-cache/v1";
          COURSIER_ARCHIVE_CACHE = "./coursier-cache/arc";
          COURSIER_JVM_CACHE = "./coursier-cache/jvm";

          # run the same build as our main derivation
          # to populate the cache with the correct set of dependencies
          buildPhase = ''
            mkdir scala-cli-home
            mkdir -p coursier-cache/v1
            mkdir -p coursier-cache/arc
            mkdir -p coursier-cache/jvm
            scala-cli compile . --native --native-version 0.4.15 --java-home=${jdk} --server=false
            scala-cli compile . --js --js-module-kind common --java-home=${jdk} --server=false
            scala-cli compile . --java-home=${jdk} --server=false
          '';

          installPhase = ''
            mkdir -p $out/coursier-cache
            cp -R ./coursier-cache $out
          '';

          outputHashAlgo = "sha256";
          outputHashMode = "recursive";
          # NOTE: don't forget to update this when deps change!
          outputHash = "sha256-hagKQcBvFdrTwSOgrDp78lkgj73iybe9dmynGmjynKI=";
        };

        scala-native-app = pkgs.stdenv.mkDerivation {
          name = "scala-native-app";
          src = ./;
          buildInputs = build-packages ++ [ coursier-cache ];

          JAVA_HOME = "${jdk}";
          SCALA_CLI_HOME = "./scala-cli-home";
          COURSIER_CACHE = "${coursier-cache}/coursier-cache/v1";
          COURSIER_ARCHIVE_CACHE = "${coursier-cache}/coursier-cache/arc";
          COURSIER_JVM_CACHE = "${coursier-cache}/coursier-cache/jvm";

          # TODO: --native-mode release-full
          buildPhase = ''
            mkdir scala-cli-home
            scala-cli --power \
              package . \
              --native \
              --native-version 0.4.15 \
              --java-home=${jdk} \
              --server=false \
              -o app 
          '';

          installPhase = ''
            mkdir -p $out/bin
            cp app $out/bin
          '';
        };

        jvm-app = pkgs.stdenv.mkDerivation {
          name = "jvm-app";
          src = ./;
          buildInputs = build-packages ++ [ coursier-cache ];

          JAVA_HOME = "${jdk}";
          SCALA_CLI_HOME = "./scala-cli-home";
          COURSIER_CACHE = "${coursier-cache}/coursier-cache/v1";
          COURSIER_ARCHIVE_CACHE = "${coursier-cache}/coursier-cache/arc";
          COURSIER_JVM_CACHE = "${coursier-cache}/coursier-cache/jvm";

          buildPhase = ''
            mkdir scala-cli-home
            scala-cli --power \
              package . \
              --standalone \
              --java-home=${jdk} \
              --server=false \
              -o app 
          '';

          installPhase = ''
            mkdir -p $out/bin
            cp app $out/bin
          '';
        };

        node-app = pkgs.stdenv.mkDerivation {
          name = "scala-js-app";
          src = ./;
          buildInputs = build-packages ++ [ node coursier-cache ];

          JAVA_HOME = "${jdk}";
          SCALA_CLI_HOME = "./scala-cli-home";
          COURSIER_CACHE = "${coursier-cache}/coursier-cache/v1";
          COURSIER_ARCHIVE_CACHE = "${coursier-cache}/coursier-cache/arc";
          COURSIER_JVM_CACHE = "${coursier-cache}/coursier-cache/jvm";

          buildPhase = ''
            mkdir scala-cli-home
            scala-cli --power \
              package . \
              --js \
              --js-module-kind common \
              --java-home=${jdk} \
              --server=false \
              -o main.js
          '';

          # We wrap `main.js` by a simple wrapper script that
          # essentially invokes `node main.js` - is this a good idea?
          # Note: the shebang below will be patched by nix
          installPhase = ''
            mkdir -p $out/bin
            cp main.js $out/bin
            cat << EOF > app
            #!/usr/bin/env sh
            ${node}/bin/node $out/bin/main.js
            EOF
            chmod +x app
            cp app $out/bin
          '';
        };

        graal-native-image-app = pkgs.stdenv.mkDerivation {
          name = "graal-native-image-app";
          src = ./;
          buildInputs = build-packages ++ [ coursier-cache ];

          JAVA_HOME = "${jdk}";
          SCALA_CLI_HOME = "./scala-cli-home";
          COURSIER_CACHE = "${coursier-cache}/coursier-cache/v1";
          COURSIER_ARCHIVE_CACHE = "${coursier-cache}/coursier-cache/arc";
          COURSIER_JVM_CACHE = "${coursier-cache}/coursier-cache/jvm";

          buildPhase = ''
            mkdir scala-cli-home
            ls ${coursier-cache}
            scala-cli --power \
              package . \
              --native-image \
              --java-home ${graal-jdk} \
              --server=false \
              --graalvm-args --verbose \
              --graalvm-args --native-image-info \
              --graalvm-args --no-fallback \
              --graalvm-args --initialize-at-build-time=scala.runtime.Statics$$VM \
              --graalvm-args --initialize-at-build-time=scala.Symbol \
              --graalvm-args --initialize-at-build-time=scala.Symbol$$ \
              --graalvm-args -H:-CheckToolchain \
              --graalvm-args -H:+ReportExceptionStackTraces \
              --graalvm-args -H:-UseServiceLoaderFeature \
              -o app \
          '';

          installPhase = ''
            mkdir -p $out/bin
            cp app $out/bin
          '';
        };

        devShell = pkgs.devshell.mkShell {
          name = "scala-native-http4s-dev-shell";
          commands =
            [ { package = scala-cli; } { package = sbt; } { package = node; } ];
          packages = build-packages ++ [ sbt metals ];
          env = [
            {
              name = "JAVA_HOME";
              value = "${jdk}";
            }
            {
              name = "LIBRARY_PATH";
              prefix = "$DEVSHELL_DIR/lib:${pkgs.openssl.out}/lib";
            }
            {
              name = "C_INCLUDE_PATH";
              prefix = "$DEVSHELL_DIR/include";
            }
            {
              name = "LLVM_BIN";
              value = "${pkgs.clang}/bin";
            }
          ];
        };

      in {
        devShells.default = devShell;

        packages = rec {
          jvm = jvm-app;
          graal = graal-native-image-app;
          native = scala-native-app;
          node = node-app;
          default = jvm;
        };

        apps = rec {
          jvm = {
            type = "app";
            program = "${jvm-app}/bin/app";
          };
          graal = {
            type = "app";
            program = "${graal-native-image-app}/bin/app";
          };
          native = {
            type = "app";
            program = "${scala-native-app}/bin/app";
          };
          node = {
            type = "app";
            program = "${node-app}/bin/app";
          };
          default = jvm;
        };
      });
}
