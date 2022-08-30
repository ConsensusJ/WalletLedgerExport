package org.consensusj.tools.ledgerexport;

import org.bitcoinj.core.Address;

/**
 *
 */
public record AddressAccount(String label, Address address, String account) {
    AddressAccount(String label, String addressString, String account) {
        this(label, Address.fromString(null, addressString), account);
    }
}
