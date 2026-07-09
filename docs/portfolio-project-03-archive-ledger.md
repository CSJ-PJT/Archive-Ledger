# Project 03 · Archive-Ledger

## Synthetic Financial Transaction Processing Backend

Archive-Ledger는 Archive-Nexus에서 발생한 제조/모빌리티 운영 이벤트를 금융성 거래로 정규화하고, 원장 기록, 정산 배치, 대사, 승인, 감사 로그를 처리하는 이벤트 기반 백엔드입니다.

실제 사용자 데이터 없이 합성 도메인 이벤트만 사용하며, 중복 수신·재처리·승인 대기·정산 불일치 같은 운영 문제를 검증할 수 있도록 설계했습니다.

## 핵심 구현

- Nexus synthetic outbox event 발행
- Ledger event ingestion과 idempotency 처리
- finance transaction 정규화
- double-entry ledger entry 생성
- high amount / high severity approval gate
- ArchiveOS external approval + fallback policy evidence
- daily settlement batch
- reconciliation result
- audit log와 Prometheus metrics

## 포트폴리오 연결

- AX 백엔드: 제조 운영 이벤트를 비용/승인/정산 흐름으로 연결
- 금융 백엔드: idempotency, 정산, 대사, 감사 로그 구현
- LLM 백엔드: RAG evidence와 fallback evidence를 승인 정책에 연결
