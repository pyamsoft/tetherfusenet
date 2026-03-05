#!/usr/bin/python3

# Copyright (C) 2025 pyamsoft
# 
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License.  You may obtain a copy
# of the License at
# 
#     http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
# License for the specific language governing permissions and limitations
# under the License.

import socket
from traceback import print_exc

FAILED_RESP: bytes = bytes([])

class NormalUdpRequest:
    def __init__(self, family):
        try:
            s = socket.socket(family, socket.SOCK_DGRAM)
            s.settimeout(3)
            self.s = s
            self.family = family
        except socket.error:
            print_exc()

    def request(
        self,
        request: bytes,
        remote_host: str,
        remote_port: int,
    ) -> bytes:
        try:
            if self.family == socket.AF_INET:
                self.s.sendto(request, (remote_host,  remote_port))
            else:
                self.s.bind(("::", 0))
                self.s.connect((remote_host,  remote_port, 0, 0))
                self.s.sendto(request, (remote_host,  remote_port, 0, 0))
            print("LOCAL:", self.s.getsockname())
            (resp, remote)= self.s.recvfrom(4096)
            print("RECV remote: ", remote)
            return resp
        except socket.error:
            print_exc()
        return FAILED_RESP

if __name__ == "__main__":
    n4 = NormalUdpRequest(socket.AF_INET)
    n6 = NormalUdpRequest(socket.AF_INET6)

    from main import build_dns_request
    dns_request = build_dns_request(1234, "cloudflare.com", "A")
    print(n4.request(dns_request, remote_host="8.8.8.8", remote_port=53))

    dns_request = build_dns_request(1234, "cloudflare.com", "AAAA")
    print(n6.request(dns_request, remote_host="2001:4860:4860::8844", remote_port=53))
