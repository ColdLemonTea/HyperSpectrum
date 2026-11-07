// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "protocol.h"
namespace tap {
class UiBridge {
public:
    ~UiBridge();
    int fd() const { return fd_; }
    int error() const { return error_; }
    void connect_if_needed(const Report&, uint64_t now);
    bool request(const Report&, Command&, uint64_t sequence);
    bool expired(uint64_t now) const;
    bool publish(const Report&);
    void disconnect();
private:
    int fd_ = -1;
    int error_ = 0;
    uint64_t next_connect_ = 0, last_request_ = 0, last_sequence_ = 0, generation_ = 0;
    bool demanded_ = false;
};
}
