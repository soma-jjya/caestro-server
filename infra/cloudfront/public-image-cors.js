// CloudFront Functions, runtime 2.0, viewer-response for /catalog/* only.
// Inspect existing function associations before attaching; see README.md.
function handler(event) {
    var response = event.response;
    response.headers['access-control-allow-origin'] = { value: '*' };
    return response;
}
