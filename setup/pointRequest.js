// pointRequest.js

// Gateway 호스트 설정
export function getGatewayHost() {
   return "20.249.64.81";
}

// 랜덤한 파트너 타입 반환 함수
function getRandomPartnerType() {
    //const types = ['MART', 'CONVENIENCE', 'ONLINE'];
    //return types[Math.floor(Math.random() * types.length)];
    return "MART";
}

// 테스트용 포인트 적립 요청 데이터 생성 함수
export function createPointRequest() {
    const partnerId = "STORE" + String(Math.floor(Math.random() * 1000)).padStart(3, '0');
    const memberId = "USER" + String(Math.floor(Math.random() * 1000)).padStart(3, '0');
    const partnerType = getRandomPartnerType();
    const amount = Math.floor(Math.random() * 900000) + 100000;

    return {
        memberId: memberId,
        partnerId: partnerId,
        partnerType: partnerType,
        amount: amount
    };
}
