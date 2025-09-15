{
  description = "Preliminary devshell support for WalletLedgerExport";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs = { self, nixpkgs, flake-utils }:
    let
    in {
      devShells = forEachSystem (system:
        let
            pkgs = import nixpkgs { inherit system; };
         in {
            default = pkgs.mkShell {
              buildInputs = [
                pkgs.jdk  # JDK 21 (We're still building with ./gradlew, so install JDK not gradle)
              ];
              shellHook = ''
                echo "Welcome to WalletLedgerExport"
              '';
            };
         });
    };
}
