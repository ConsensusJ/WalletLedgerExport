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
package org.consensusj.ledgerexport.lib;

import org.bitcoinj.core.Address;

/**
 * Address to Account mapping record (generally read from a CSV file)
 */
public record AddressAccount(String label, Address address, String account) {
    public AddressAccount(String label, String addressString, String account) {
        this(label, Address.fromString(null, addressString), account);
    }
}
