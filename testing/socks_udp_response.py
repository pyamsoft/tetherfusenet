#!/usr/bin/python3

# socks_udp_response.py - simple tool to test UDP support of SOCKS5 proxy.
#
# udpchk.py - simple tool to test UDP support of SOCKS5 proxy.
#
# Copyright (C) 2025 pyamsoft
# Copyright (C) 2016-2025 Zhuofei Wang <semigodking@gmail.com>
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
import socks
from traceback import print_exc

FAILED_RESP: bytes = bytes([])

class ProxyUdpRequest:
    def __init__(
        self,
        proxy_server_host: str, 
        proxy_server_port: int, 
        user: str | None = None, 
        pwd: str | None = None,
    ):
        self.s = socks.socksocket(socket.AF_INET, socket.SOCK_DGRAM)

        try:
            # The SOCKS server resolves DNS for us
            rdns=True

            self.s.set_proxy(
                socks.SOCKS5, 
                proxy_server_host,
                proxy_server_port,
                rdns,
                user,
                pwd,
            )
        except socks.ProxyError:
            print_exc()
        except socket.error:
            print_exc()

    def request(
        self,
        request: bytes,
        remote_host: str,
        remote_port: int,
    ) -> bytes:
        try:
            print("TRY SEND: ", remote_host, remote_port)
            self.s.sendto(request, (remote_host,  remote_port))
            (resp, _)= self.s.recvfrom(4096)
            return resp
        except socks.ProxyError:
            print_exc()
        except socket.error:
            print_exc()
        return FAILED_RESP
