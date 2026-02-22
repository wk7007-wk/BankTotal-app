# BankTotal 프로젝트 규칙

## 앱 개요
계좌잔고 위젯 + BBQ SFA BLOCK 금액 + 가계부(입출금 내역 관리)

## 개발 방침
- **오류/누락 절대 불가** — 사업 관련 금액 데이터. 수정 시 기존 기능 깨지지 않도록 반드시 검증
- 금액 표시, 계산, API 호출 로직 변경 시 이중 확인
- **SMS 권한 사용 금지** — RECEIVE_SMS/READ_SMS 권한은 Play Protect 차단 유발. NotificationListenerService로 메시지앱 알림 수신하여 처리

## 가계부 기능 (Firebase 기반)
- **"이원규" 필터**: 입금/출금 모두 "이원규" 이름은 통장간 이동으로 제외 (조회 시)
- **입금**: 날짜별 합계 + 리스트식 표기
- **출금**: 리스트식 표기 + 같은 출금명은 카테고리 그룹핑
- **조회**: 날짜별 리스트, 카테고리별 합계, 기간별(주간/월간/커스텀)

## 아키텍처 (알림 수신 기반)
- **NotificationListenerService** → 메시지앱(삼성/구글/기본) + 신한앱 알림 수신 → Firebase에 원문 + 파싱 결과 저장
- **지원 은행**: KB국민, 하나, 신협, 신한, 우리, 농협, 기업, 카카오, 토스 (메시지앱 알림으로 자동 식별)
- **로컬 Room**: 계좌 잔고(위젯용)만 유지, 거래내역은 Firebase
- **Firebase 경로**: `/banktotal/transactions/{id}`, `/banktotal/accounts/{id}`
- **거래상대**: SMS 원문 저장 → 패턴 분석으로 점진적 파싱 개선
- **분석**: curl로 Firebase 데이터 읽어서 로그 분석/개선 가능

## WebView + GitHub Pages 구조
- **UI는 웹(`docs/index.html`)에서 처리** — 네이티브 UI 최소화
- WebView가 GitHub Pages 대시보드 로드, 오프라인 캐시 폴백
- NativeBridge: `getAccountsJson()`, `getPermissions()`, `openNotificationSettings()`, `openAccessibilitySettings()`, `getAppVersion()`
- UI 변경 시 `docs/index.html` 수정 후 push → 즉시 반영 (APK 재빌드 불필요)

## 로그분석 시스템
- `LogWriter.kt` → Firebase `/banktotal/logs`에 태그별 로그 저장 (최근 200건 순환)
- 태그: `[TX]` 거래수신, `[PARSE]` 파싱결과, `[ERR]` 에러, `[SYS]` 시스템
- 웹 대시보드 "로그" 탭에서 실시간 모니터링
- 로그 분석 → 파싱 개선 → 테스트 → 배포 사이클

## GitHub
- 레포: `wk7007-wk/BankTotal-app` (프로젝트명과 레포명 다름 주의)
- GitHub Pages: `https://wk7007-wk.github.io/BankTotal-app/` (docs/ 폴더)
