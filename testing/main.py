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

from __future__ import annotations
from normal_nonproxy_udp_response import normal_udp_request
from socks_udp_response import proxy_udp_request
from struct import pack, unpack
from dataclasses import dataclass
from pprint import pprint
import socket

remote_host: str = "dns.google"
remote_port: int = 53

@dataclass
class DNSResult:
    dns_type: int
    dns_class: int
    ttl: int
    address: str


@dataclass
class DNSResponse:
    transaction_id: int
    query_or_response: int
    opcode: int
    truncation_bit: int
    authoritative_answer_bit: int
    recursion_desired_bit: int
    recursion_available_bit: int
    response_code: int
    question_count: int
    answer_count: int
    authority_count: int
    additional_count: int
    results: list[DNSResult]

    def matches(self, other: DNSResponse) -> list[str]:
        mismatched: list[str] = []

        if self.transaction_id != other.transaction_id:
            mismatched.append("transaction_id")
        if self.query_or_response != other.query_or_response:
            mismatched.append("query_or_response")
        if self.opcode != other.opcode:
            mismatched.append("opcode")
        if self.truncation_bit != other.truncation_bit:
            mismatched.append("truncation_bit")
        if self.authoritative_answer_bit != other.authoritative_answer_bit:
            mismatched.append("authoritative_answer_bit")
        if self.recursion_desired_bit != other.recursion_desired_bit:
            mismatched.append("recursion_desired_bit")
        if self.recursion_available_bit != other.recursion_available_bit:
            mismatched.append("recursion_available_bit")
        if self.response_code != other.response_code:
            mismatched.append("response_code")
        if self.question_count != other.question_count:
            mismatched.append("question_count")
        if self.answer_count != other.answer_count:
            mismatched.append("answer_count")
        if self.authority_count != other.authority_count:
            mismatched.append("additional_count")
        if self.additional_count != other.additional_count:
            mismatched.append("additional_count")

        if len(self.results) != len(other.results):
            mismatched.append("len(results)")

        for res_1 in self.results:
            matched: DNSResult | None = None
            for res_2 in other.results:
                if res_1.address == res_2.address:
                    matched = res_2
                    break

            if not matched:
                mismatched.append(f"Address: {res_1.address}")
            else:
                if res_1.dns_class != matched.dns_class:
                    mismatched.append(f"dns_class: {res_1.address}")
                if res_1.dns_type != matched.dns_type:
                    mismatched.append(f"dns_type {res_1.address}")

        return mismatched

fake_normal_response = DNSResponse(
    transaction_id=4660,
    query_or_response=1,
    opcode=0, 
    truncation_bit=0, 
    authoritative_answer_bit=0, 
    recursion_desired_bit=1, 
    recursion_available_bit=1, 
    response_code=0, 
    question_count=1, 
    answer_count=6, 
    authority_count=0, 
    additional_count=0, 
    results=[
        DNSResult(
            dns_type=1, 
            dns_class=1, 
            ttl=233, 
            address='96.7.128.198',
        ), 
        DNSResult(
            dns_type=1, 
            dns_class=1, 
            ttl=233, 
            address='23.215.0.138',
        ), 
        DNSResult(
            dns_type=1, 
            dns_class=1, 
            ttl=233, 
            address='23.215.0.136',
        ), 
        DNSResult(
            dns_type=1, 
            dns_class=1, 
            ttl=233, 
            address='23.192.228.84',
        ), 
        DNSResult(
            dns_type=1, 
            dns_class=1, 
            ttl=233, 
            address='96.7.128.175',
        ), 
        DNSResult(
            dns_type=1, 
            dns_class=1, 
            ttl=233, 
            address='23.192.228.80',
        )
    ]
)

fake_proxy_response = DNSResponse(
    transaction_id=4660,
    query_or_response=1,
    opcode=0,
    truncation_bit=0,
    authoritative_answer_bit=0,
    recursion_desired_bit=1,
    recursion_available_bit=1,
    response_code=0,
    question_count=1,
    answer_count=6,
    authority_count=0,
    additional_count=0,
    results=[
        DNSResult(
            dns_type=1,
            dns_class=1,
            ttl=248,
            address='96.7.128.175',
        ),
        DNSResult(
            dns_type=1,
            dns_class=1,
            ttl=248,
            address='23.215.0.136',
        ),
        DNSResult(
            dns_type=1,
            dns_class=1,
            ttl=248,
            address='23.192.228.84',
        ),
        DNSResult(
            dns_type=1, 
            dns_class=1, 
            ttl=248, 
            address='23.215.0.138',
        ), 
        DNSResult(
            dns_type=1, 
            dns_class=1, 
            ttl=248, 
            address='96.7.128.198',
        ), 
        DNSResult(
            dns_type=1, 
            dns_class=1, 
            ttl=248, 
            address='23.192.228.80',
        ),
    ],
)

def encode_domain_name(domain: str) -> bytes:
    parts: list[str] = domain.split(".")
    encoded_name: bytes = b""

    for part in parts:
        encoded_name += bytes([len(part)]) + part.encode("utf-8")
    encoded_name += b"\0"
    return encoded_name

def build_dns_request(transaction_id: int, domain_name: str) -> bytes:
    # Standard DNS request
    # 1) AA bit - set to 0, we are a request not an answer
    # 2) TC - truncation, set to 0
    # 3) RD bit - recursion bit, yes we want to look up all known DNS servers until a match is found
    # 4) RA bit - recursion available, we are not a server, set to 0
    flags: int = 0x0100

    # One question, what is the IP of the domain_name
    num_questions: int = 1

    # No answers
    num_answers: int = 0

    # No authority records
    num_authority_records: int = 0

    # No additional records
    num_additional_records: int = 0

    # Pack the header as bytes
    header = pack(
        ">HHHHHH",
        transaction_id,
        flags,
        num_questions,
        num_answers,
        num_authority_records,
        num_additional_records,
    )

    # A record
    query_type: int = 1

    # Class IN
    query_class: int = 1

    question = pack(
        ">HH",
        query_type,
        query_class,
    )

    encoded_domain = encode_domain_name(domain_name)

    return header + encoded_domain + question

def parse_dns_response(resp: bytes) -> DNSResponse:
    # DNS header fields (12 bytes)
    header_offset = 12  # Start after the DNS header
    header = resp[:header_offset]

    # Unpack the header (using struct to decode the binary data)
    transaction_id, flags, question_count, answer_count, authority_count, additional_count = unpack('>HHHHHH', header)

    # Extract header fields
    qr = (flags >> 15) & 0x1      # QR (Query/Response)
    opcode = (flags >> 11) & 0xF  # Opcode
    aa = (flags >> 10) & 0x1      # AA (Authoritative Answer)
    tc = (flags >> 9) & 0x1       # TC (Truncation)
    rd = (flags >> 8) & 0x1       # RD (Recursion Desired)
    ra = (flags >> 7) & 0x1       # RA (Recursion Available)
    rcode = flags & 0xF           # RCODE (Response Code)

    # Parse question section
    offset = header_offset
    for _ in range(question_count):
        q_len = resp[offset]
        while q_len != 0:
            offset += q_len + 1
            q_len = resp[offset]
        # Skip the null byte
        offset += 1

        # Skip QTYPE
        offset += 2

        # Skip QCLASS
        offset += 2

    # Parsing the answer section
    answers = []
    for _ in range(answer_count):
        # Skip the name field
        offset += 2
        
        dns_type, dns_class, ttl, rdlength = unpack('>HHIH', resp[offset:offset + 10])
        offset += 10

        # A record
        if dns_type == 1:
            ip_address = socket.inet_ntoa(resp[offset:offset + rdlength])
        # AAAA record
        elif dns_type == 28:
            ip_address = socket.inet_ntop(socket.AF_INET6, resp[offset:offset + rdlength])
        else:
            raise Exception(f"Unexpected DNS response type: {dns_type}")
        
        answers.append((dns_type, dns_class, ttl, ip_address))
        offset += rdlength

    return DNSResponse(
        transaction_id=transaction_id,
        answer_count=answer_count,
        query_or_response=qr,
        question_count=question_count,
        opcode=opcode,
        authoritative_answer_bit=aa,
        truncation_bit=tc,
        recursion_desired_bit=rd,
        recursion_available_bit=ra,
        response_code=rcode,
        authority_count=authority_count,
        additional_count=additional_count,
        results=[DNSResult(
            dns_type=dns_type,
            dns_class=dns_class,
            ttl=ttl,
            address=ip_address,
        ) for (dns_type, dns_class, ttl, ip_address) in answers]
    )

def test_dns(transaction_id: int, domain_name: str) -> int:
    dns_request = build_dns_request(transaction_id, domain_name)

    normal_response: DNSResponse | None = None
    proxy_response: DNSResponse | None = None

    # Fake responses
    # normal_response = fake_normal_response
    # proxy_response = fake_proxy_response

    if not normal_response:
        b = normal_udp_request(
            request=dns_request,
            remote_host=remote_host,
            remote_port=remote_port,
        )
        print("NORMAL BYTES: ", b)
        if not b or len(b) <= 0:
            print("BAD NORMAL RESPONSE")
            return 1
        normal_response = parse_dns_response(b)
        print("NORMAL RESP: ", normal_response)

    if not proxy_response:
        b = proxy_udp_request(
            request=dns_request,
            remote_host=remote_host,
            remote_port=remote_port,
            proxy_server_host="192.168.49.1",
            proxy_server_port=8229,
        )
        if not b or len(b) <= 0:
            print("BAD PROXY RESPONSE")
            return 2
        proxy_response = parse_dns_response(b)
        pprint(proxy_response)

    if normal_response and proxy_response:
        if domain_name == "example.com":
            mismatched_fields: list[str] = normal_response.matches(proxy_response)
            if len(mismatched_fields) > 0:
                print(f"MISMATCH IN RESPONSES: {mismatched_fields}")
                return 1

    return 0

def main(args: list[str]) -> int:
    if not args:
        print("Specify at least 1 domain name for DNS")
        return 1

    transaction_id = 0x1234
    for domain_name in args:
        print(f"DNS: {domain_name}")
        test_dns(transaction_id, domain_name)
        print("")

    return 0

if __name__ == "__main__":
    import sys

    exit_code = main(sys.argv[1:])
    sys.exit(exit_code)
