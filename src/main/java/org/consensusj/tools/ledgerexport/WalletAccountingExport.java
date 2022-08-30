package org.consensusj.tools.ledgerexport;

import foundation.omni.json.pojo.BitcoinTransactionInfo;
import foundation.omni.json.pojo.OmniTransactionInfo;
import foundation.omni.rpc.OmniClient;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.consensusj.bitcoin.json.pojo.WalletTransactionInfo;
import org.consensusj.bitcoin.jsonrpc.RpcURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tool to export Omni Core wallet transactions to ledger-cli plain-text files.
 */
public class WalletAccountingExport {
    private static final Logger log = LoggerFactory.getLogger(WalletAccountingExport.class);
    private final PrintStream out;

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
        // Get Consolidated Transaction Information from Bitcoin/Omni JSON-RPC server
        var consolidator = new Consolidator(client);
        var consTxs = consolidator.fetch();

        List<AddressAccount> addressAccounts = (accountMapFile != null)
                ? readAddressAccountCSV(accountMapFile)
                : Collections.emptyList();


        // Import them to a list of LedgerTransaction
        var importer = new TransactionImporter(addressAccounts);
        var list = importer.importTransactions(consTxs);

        // Write a list of LedgerTransaction to an output stream
        var tool = new WalletAccountingExport(out);
        tool.print(list);
    }

    /**
     * Service for writing LedgerTransaction to an output stream.
     */
    public WalletAccountingExport(PrintStream out) {
        this.out = out;
    }
    
    void print(List<LedgerTransaction> txs) {
        txs.stream()
            .map(LedgerTransaction::toString)
            .forEach(out::println);
    }

    // Simple CSV parsing
    // TODO: Use a CSV library to handle commas, quotes, etc
    static List<AddressAccount> readAddressAccountCSV(File file) {
        try {
            return Files.lines(file.toPath())
                    .skip(1)    // skip column headers
                    .map(line -> line.split(","))
                    .map(WalletAccountingExport::arrayToAA)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Return an AddressAccount if account is specified, empty otherwise
    private static Optional<AddressAccount> arrayToAA(String[] a) {
        return a.length >= 3
                ? Optional.of(new AddressAccount(a[0], a[1], a[2]))
                : Optional.empty();
    }
}
