package org.consensusj.tools.ledgerexport;

import foundation.omni.json.pojo.BitcoinTransactionInfo;
import foundation.omni.json.pojo.OmniTransactionInfo;
import foundation.omni.rpc.OmniClient;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.consensusj.bitcoin.jsonrpc.RpcURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tool to export Omni Core wallet transactions to ledger-cli plain-text files.
 */
public class WalletAccountingExport {
    private static final Logger log = LoggerFactory.getLogger(WalletAccountingExport.class);

    private final OmniClient client;

    public static void main(String[] args) throws IOException {
        String net = args.length > 0 ? args[0] : "mainnet";
        String user = "bitcoinrpc";
        String password = "pass";
        OmniClient client = switch (net) {
            case "mainnet" -> new OmniClient(MainNetParams.get(), RpcURI.getDefaultMainNetURI(), user, password);
            case "testnet" -> new OmniClient(TestNet3Params.get(), RpcURI.getDefaultTestNetURI(), user, password);
            case "regtest" -> new OmniClient(RegTestParams.get(), RpcURI.getDefaultRegTestURI(), user, password);
            default -> throw new IllegalArgumentException("invalid network");
        };
        var tool = new WalletAccountingExport(client);
        var consTxs = tool.fetch();
        var list = consTxs.stream()
                .map(LedgerTransaction::fromConsolidated)
                .toList();
        tool.print(list);
    }

    /**
     *
     */
    public WalletAccountingExport(OmniClient client) {
        this.client = client;
    }

    /**
     * Return a list of ConsolidatedTransaction sorted by time
     */
    List<ConsolidatedTransaction> fetch() throws IOException {
        // Get Bitcoin transactions and group by transaction ID (there can be multiple objects per tx in some cases)
        Map<Sha256Hash, List<BitcoinTransactionInfo>> bitcoinTxs = client.listTransactions()
                .stream()
                .peek(bt -> {
                    if (bt.getComment().isPresent()) {
                        log.warn("Found comment: {}", bt.getComment());
                    }
                })
                .collect(Collectors.groupingBy(BitcoinTransactionInfo::getTxId));

        // Get Omni Transactions and index by transaction ID
        Map<Sha256Hash, OmniTransactionInfo> omniTxs = client.omniListTransactions(Integer.MAX_VALUE)
                .stream()
                .peek(ot -> log.info(ot.toString()))
                .collect(Collectors.toMap(OmniTransactionInfo::getTxId, Function.identity()));

        return bitcoinTxs.entrySet()
                .stream()
                .map(e -> new ConsolidatedTransaction(e.getValue(), omniTxs.get(e.getKey())))
                .sorted(Comparator.comparing(ConsolidatedTransaction::time))
                .toList();
    }

    void print(List<LedgerTransaction> txs) {
        txs.stream()
            .map(LedgerTransaction::toString)
            .forEach(System.out::println);
    }
}
