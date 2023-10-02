{
  description = "virtual environments";

  inputs.devshell.url = "github:numtide/devshell";
  inputs.flake-utils.url = "github:numtide/flake-utils";

  inputs.flake-compat = {
    url = "github:edolstra/flake-compat";
    flake = false;
  };

  outputs = { self, flake-utils, devshell, nixpkgs, ... }:
    flake-utils.lib.eachDefaultSystem (system: {
      devShell = let
        pkgs = import nixpkgs {
          inherit system;

          overlays = [ devshell.overlays.default ];
        };
        jdk = pkgs.jdk17;
      in pkgs.devshell.mkShell {
        name = "fs2-live-server-dev-shell";
        packages = with pkgs; [
          jdk
          s2n
          zlib
          s2n-tls
          openssl
          clang
          llvmPackages.libcxxabi
          coreutils
          scala-cli
        ];
        env = [
          {
            name = "JAVA_HOME";
            value = "${jdk.outPath}";
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
    });
}
