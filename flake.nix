{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    systems.url = "github:nix-systems/default";
    devenv.url = "github:cachix/devenv";
    devenv.inputs.nixpkgs.follows = "nixpkgs";
  };

  nixConfig = {
    extra-trusted-public-keys = "devenv.cachix.org-1:w1cLUi8dv3hnoSPGAuibQv+f9TZLr6cv/Hm9XgU50cw=";
    extra-substituters = "https://devenv.cachix.org";
  };

  outputs =
    {
      self,
      nixpkgs,
      devenv,
      systems,
      ...
    }@inputs:
    let
      forEachSystem = nixpkgs.lib.genAttrs (import systems);
    in
    {
      packages = forEachSystem (system: {
        devenv-up = self.devShells.${system}.default.config.procfileScript;
        devenv-test = self.devShells.${system}.default.config.test;
      });

      devShells = forEachSystem (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
        in
        {
          default = devenv.lib.mkShell {
            inherit inputs pkgs;
            modules = [
              {
                # for IDE
                env.SCALA_CLI_POWER = true;

                languages.nix.enable = true;
                languages.scala.enable = true;

                packages = with pkgs; [ jq ];

                enterShell = ''
                  scala-cli config power true
                '';

                scripts = {
                  compile.exec = ''
                    scala-cli compile . 
                  '';

                  run.exec = ''
                    scala-cli run . 
                  '';

                  git-clean.exec = ''
                    git clean -Xdf
                  '';

                  update-deps.exec = ''
                    scala-cli dependency-update . 
                    scala-cli dependency-update . --all 
                  '';

                  fix.exec = ''
                    echo 'Running scalafmt'
                    scala-cli fmt .

                    echo 'Running scalafix'
                    scala-cli fix . 
                  '';

                  show-config.exec = ''
                    scala-cli config --dump | jq .
                  '';
                };
              }
            ];
          };
        }
      );
    };
}
