// Transport-injected workflow: the same code runs in k6 and deterministic offline tests.
const admin = '/b2b/admin/projects';
const windows = [0.05, 0.28, 0.51, 0.74].map(y => ({ x: 0.1, y, w: 0.8, h: 0.2, rot: 0 }));

export function workflow(c, io) {
  function call(method, path, body, credential, stage, expected = 200, raw = false) {
    const response = io.request({ method, path, body, credential, stage, raw });
    const ok = response.status === expected;
    io.measure('request_failed', !ok, { stage });
    if (!ok) throw new Error(`${stage}: HTTP ${response.status}, expected ${expected}`);
    return expected === 204 ? null : response.json();
  }

  function cleanup(data) {
    let failed = false;
    for (const owned of data.projects) {
      try {
        if (!owned.id || !owned.slug.startsWith(`lt-b2b-${data.run}-`)) throw new Error('Unsafe cleanup record');
        const current = call('GET', `${admin}/${encodeURIComponent(owned.id)}`, null, 'studio', 'cleanup');
        if (current.id !== owned.id || current.slug !== owned.slug || current.description !== data.marker) {
          throw new Error('Project ownership marker changed; cleanup refused');
        }
        if (current.status === 'LIVE') call('POST', `${admin}/${owned.id}/pause`, null, 'studio', 'cleanup');
        call('DELETE', `${admin}/${owned.id}`, null, 'studio', 'cleanup', 204);
      } catch (error) {
        failed = true;
        // Only own generated ID/slug: never dump HTTP bodies, credentials or token responses.
        io.log(`Cleanup required: ${owned.id} ${owned.slug}: ${error.message}`);
      }
    }
    io.measure('cleanup_failed', failed);
    if (failed) throw new Error('Test project cleanup incomplete; see own IDs above');
  }

  function setup(run, photo) {
    if (!/^[a-z0-9-]{1,22}$/.test(run)) throw new Error('Invalid generated run id');
    const data = { run, marker: `k6-b2b-219:${run}`, projects: [] };
    try {
      const image = call('POST', '/catalog/studio/images', photo, 'studio', 'setup', 201, true);
      for (let i = 0; i < c.projects; i++) {
        const slug = `lt-b2b-${run}-${i}`;
        const project = call('POST', admin, { name: slug, slug, description: data.marker }, 'studio', 'setup', 201);
        // Register immediately, so a later setup failure still cleans this exact project.
        data.projects.push({ id: project.id, slug });
        io.log(`Created test project: ${project.id} ${slug}`);
        call('PUT', `${admin}/${project.id}/frames`, { frames: [
          { name: 'Load fixture', layout: 'STRIP', imageId: image.imageId, windows },
        ] }, 'studio', 'setup');
        call('POST', `${admin}/${project.id}/publish`, null, 'studio', 'setup');
      }
      return data;
    } catch (error) {
      cleanup(data); // k6 does not invoke teardown when setup throws.
      throw error;
    }
  }

  function journey(data, iteration, photo) {
    const project = data.projects[iteration % data.projects.length];
    let auth;
    let code;
    let completed = false;
    const started = io.now();
    function token() {
      if (!auth) throw new Error('Missing guest token');
      if (io.expiresAt(auth.accessToken) <= io.now() + 30000) {
        auth = call('POST', '/auth/refresh', { refreshToken: auth.refreshToken }, null, 'refresh');
        if (io.expiresAt(auth.accessToken) <= io.now() + 30000) throw new Error('Refreshed token expires too soon');
      }
      return auth.accessToken;
    }
    function read() {
      return call('GET', `/b2b/projects/${project.slug}/fills/${code}`, null, token(), 'read');
    }
    try {
      const entry = call('GET', `/b2b/projects/${project.slug}`, null, null, 'entry');
      const frame = entry.frames[iteration % entry.frames.length];
      if (!frame || !frame.imageUrl || frame.windows.length !== 4) throw new Error('No valid public frame');
      auth = call('POST', '/auth/guest', { deviceId: `lt-${data.run}-${iteration}` }, null, 'guest');
      const created = call('POST', `/b2b/projects/${project.slug}/fills`,
        { frameId: frame.id, frameVersion: frame.version }, token(), 'create', 201);
      code = created.fill.code;
      if (!/^[A-Z0-9]+$/.test(code || '')) throw new Error('Invalid fill code');
      for (let slot = 0; slot < 4; slot++) {
        // User think time, with visible-page polling. Upload requests themselves do not overlap polling.
        let remaining = c.hold / 4;
        while (remaining > 0) {
          const delay = Math.min(c.poll, remaining);
          io.sleep(delay);
          remaining -= delay;
          read();
        }
        call('PUT', `/fills/${code}/slots/${slot}`, photo, token(), 'upload', 200, true);
      }
      const final = read();
      completed = final.fill.slots.length === 4 && final.fill.slots.every(slot => Boolean(slot.imageUrl));
      if (!completed) throw new Error('Four filled slots were not returned');
    } catch (error) {
      // Stage/status counters provide the detail without one log line per failed request under load.
      io.measure('journey_error', 1);
    } finally {
      io.measure('journey_success', completed);
      io.measure('journey_duration', io.now() - started);
      // Retain sessions until project teardown: deleting every iteration would distort steady-state DB size.
    }
  }
  return { setup, cleanup, journey };
}
