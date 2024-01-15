{
  description = "Purely functional live server";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    typelevel-nix.url = "github:typelevel/typelevel-nix";
    flake-utils.follows = "typelevel-nix/flake-utils";
    scala-dev.url = "github:ramytanios/nix-lib";
  };

  outputs = { self, nixpkgs, typelevel-nix, flake-utils, scala-dev, ... }:
    let
      inherit (flake-utils.lib) mkApp;

      pname = "live-server";
      version = if (self ? rev) then self.shortRev else self.dirtyShortRev;

      eachSystem = nixpkgs.lib.genAttrs flake-utils.lib.defaultSystems;

      mkPackages = pkgs:
        scala-dev.lib.mkBuildScalaApp pkgs {
          inherit version;
          inherit pname;
          src = ./src;
          supported-platforms = [ "jvm" ];
          sha256 = "sha256-+VURwmMsEOPKHQ9cQSxzxkkIFxLDfgcPUh39KDQ2wZU=";
        };

      mkPckgs = system: import nixpkgs { inherit system; };

    in {
      # devshells
      devShells = eachSystem (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ typelevel-nix.overlay ];
          };
        in {
          default = pkgs.devshell.mkShell {
            imports = [ typelevel-nix.typelevelShell ];
            name = "${pname}-dev-shell";
            typelevelShell = {
              jdk.package = pkgs.jdk;
              nodejs.enable = false;
              native.enable = true;
              native.libraries = with pkgs; [ zlib s2n-tls openssl ];
            };
            packages = with pkgs; [ which ];
          };
        });

      # packages
      packages = eachSystem (system: mkPackages (mkPckgs system));

      # apps
      apps = eachSystem (system:
        builtins.mapAttrs (_: drv:
          (mkApp {
            inherit drv;
            name = pname;
          })) (mkPackages (mkPckgs system)));

      #overlays
      overlays = {
        default = final: _: { ${pname} = (mkPackages final).jvm; };
      };

      # checks 
      checks = self.packages;
    };
}
