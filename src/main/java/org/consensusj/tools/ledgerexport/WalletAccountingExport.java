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
package org.consensusj.tools.ledgerexport;

import foundation.omni.rpc.OmniClient;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.consensusj.bitcoin.jsonrpc.RpcURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

// TODO: Use pico-cli to manage command-line options
/**
 * Tool to export Omni Core wallet transactions to ledger-cli plain-text files.
 */
public class WalletAccountingExport {
    private static final Logger log = LoggerFactory.getLogger(WalletAccountingExport.class);

    /**
     * Main entry point for command-line tool
     * @param args command-line arguments
     * @throws IOException if an error occurred communicating with the server
     */
    public static void main(String[] args) throws IOException {
        String net = args.length > 0 ? args[0] : "mainnet";
        PrintStream out = args.length >= 3 && args[1].equals("-o")
                ? new PrintStream(new FileOutputStream(args[2]))
                :  System.out;
        File accountMapFile = args.length >= 5 && args[3].equals("-m")
                ? new File(args[4])
                :  null;
        String user = "bitcoinrpc";
        String password = "pass";
        OmniClient client = switch (net) {
            case "mainnet" -> new OmniClient(MainNetParams.get(), RpcURI.getDefaultMainNetURI(), user, password);
            case "testnet" -> new OmniClient(TestNet3Params.get(), RpcURI.getDefaultTestNetURI(), user, password);
            case "regtest" -> new OmniClient(RegTestParams.get(), RpcURI.getDefaultRegTestURI(), user, password);
            default -> throw new IllegalArgumentException("invalid network");
        };

        AccountingExporter exporter = new OmniLedgerExporter(client, accountMapFile, out);
        
        exporter.initialize();
        List<TransactionData> transactions = exporter.collectData();
        List<LedgerTransaction> entries = exporter.convertToLedger(transactions);

        // TODO: Add command-line option to enable output-filtering by full/partial account string
        boolean filter = false;
        String filterAccount = "Income:Consulting";
        List<LedgerTransaction> outputEntries = filter
                                    ? entries.stream()
                                        .filter(t -> t.matchesAccount(filterAccount))
                                        .toList()
                                    : entries;
        exporter.output(outputEntries);
    }
}
