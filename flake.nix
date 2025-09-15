{
  description = "Preliminary devshell support for WalletLedgerExport";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs = { self, nixpkgs, flake-utils }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" "x86_64-darwin" ];
      forEachSystem = f: builtins.listToAttrs (map (system: {
        name = system;
        value = f system;
      }) systems);
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
         }
      );
     packages = forEachSystem (system: {
         wallet-ledger-export =
           let
             pkgs = import nixpkgs {
               inherit system;
             };
             mainProgram = "wallet-ledger-export";
             gradle = pkgs.gradle_8;
             jre = pkgs.jdk21_headless;  # JRE to run the example with
             self2 = pkgs.stdenv.mkDerivation (_finalAttrs: {
               pname = "wallet-ledger-export";
               version = "0.0.2-SNAPSHOT";
               meta = {
                 inherit mainProgram;
               };

               src = self;  # project root is source

               nativeBuildInputs = [gradle pkgs.makeWrapper];

               mitmCache = gradle.fetchDeps {
                 pkg = self2;
                 # update or regenerate this by running:
                 #  $(nix build .#wallet-ledger-export.mitmCache.updateScript --print-out-paths)
                 data = ./nix-deps.json;
               };

               gradleBuildTask = "ledger-export-tool:installDist";

               gradleFlags = [ "--info --stacktrace" ];

               # will run the gradleCheckTask (defaults to "test")
               doCheck = false;

               installPhase = ''
                  mkdir -p $out/{bin,share/${mainProgram}/lib}
                  cp ledger-export-tool/build/install/LedgerExport/lib/*.jar $out/share/${mainProgram}/lib
                  # Compute CLASSPATH: all .jar files in $out/share/${mainProgram}/lib
                  export MYPATH=$(find $out/share/${mainProgram}/lib -name "*.jar" -printf ':%p' | sed 's|^:||')  # Colon-separated, no leading :

                  makeWrapper ${jre}/bin/java $out/bin/${mainProgram} \
                        --add-flags "-cp $MYPATH org.consensusj.ledgerexport.tool.WalletAccountingExport" \
                '';
               });
           in
             self2;
       });
    };
}
