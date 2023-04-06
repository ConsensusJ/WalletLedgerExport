/*
 * Copyright 2022 M. Sean Gilligan.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.consensusj.ledgerexport.tool;

import foundation.omni.rpc.OmniClient;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.consensusj.bitcoin.jsonrpc.RpcConfig;
import org.consensusj.bitcoin.jsonrpc.RpcURI;
import org.consensusj.bitcoin.jsonrpc.bitcoind.BitcoinConfFile;
import org.consensusj.ledgerexport.lib.AccountingExporter;
import org.consensusj.ledgerexport.lib.LedgerTransaction;
import org.consensusj.ledgerexport.lib.OmniLedgerExporter;
import org.consensusj.ledgerexport.lib.TransactionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Tool to export Bitcoin Core (or Omni Core) wallet transactions to ledger-cli plain-text files.
 */
@Command(name="WalletLedgerExport",
        description = "Export wallet ledger as double-entry transactions",
        version = "0.0.2",
        mixinStandardHelpOptions = true)
public class WalletAccountingExport implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(WalletAccountingExport.class);

    /**
     * Main entry point for command-line tool
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new WalletAccountingExport());
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    /**
     * Command-line options (parsed by pico-cli)
     */
    public static class ExportOptions {
        @Option(names = {"-n", "--network"},
                description = "Network: mainnet, testnet, signet, or regtest (default is \"mainnet\")",
                defaultValue = "mainnet")
        String net;
        @Option(names = {"-o", "--output"},
                description = "Output file path (default is stdout)")
        File outputFile;
        @Option(names = {"-m", "--account-map"},
                description = "Path to account-mapping CSV file (default is none)")
        File accountMapFile;
        @Option(names = {"-w", "--wallet"},
                description = "Wallet name (default is \"\")",
                defaultValue = "")
        String wallet;
        @Option(names = {"-f", "--account-filter"},
                description = "Account filter for output, e.g. \"Income:Consulting\" (default is none)")
        String filterAccount;
    }

    @Mixin
    ExportOptions options;

    /**
     * Export plaintext double-entry accounting entries to either {@code System.out} or {@link ExportOptions#outputFile}.
     * @return status code for {@link System#exit(int)}
     * @throws IOException if problem communicating with the server
     */
    public Integer call() throws IOException {
        final PrintStream out = options.outputFile != null
                ? new PrintStream(new FileOutputStream(options.outputFile))
                :  System.out;

        // Read password from standard bitcoin.conf file
        RpcConfig passwordConfig = BitcoinConfFile.readDefaultConfig().getRPCConfig();
        String username = passwordConfig.getUsername();
        String password = passwordConfig.getPassword();
        RpcConfig config = switch (options.net) {
            case "mainnet" -> new RpcConfig(MainNetParams.get(), RpcURI.getDefaultMainNetURI().resolve(options.wallet), username, password);
            case "testnet" -> new RpcConfig(TestNet3Params.get(), RpcURI.getDefaultTestNetURI().resolve(options.wallet), username, password);
            case "regtest" -> new RpcConfig(RegTestParams.get(), RpcURI.getDefaultRegTestURI().resolve(options.wallet), username, password);
            default -> throw new IllegalArgumentException("invalid network");
        };
        log.info("Connecting to {}", config.getURI());
        OmniClient client = new OmniClient(config);

        AccountingExporter exporter = new OmniLedgerExporter(client, options.accountMapFile, out);

        exporter.initialize();
        List<TransactionData> transactions = exporter.collectData();
        List<LedgerTransaction> entries = exporter.convertToLedger(transactions);

        // If options.filterAccount was present, only output entries that match specified account
        Predicate<LedgerTransaction> predicate = (options.filterAccount != null)
                ? t -> t.matchesAccount(options.filterAccount)
                : t -> true;
        List<LedgerTransaction> outputEntries = entries.stream().filter(predicate).toList();
        exporter.output(outputEntries);
        return 0;
    }
}
