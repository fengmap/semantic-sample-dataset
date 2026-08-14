const API = '/api/demo/maps';
const state = {
    mapId: '911907', floors: [], floorName: null, entities: [], selected: null,
    start: null, target: null, route: null, bounds: null, camera: null,
    dragging: false, dragPoint: null
};

const $ = id => document.getElementById(id);
const canvas = $('mapCanvas');
const context = canvas.getContext('2d');

async function request(url, options) {
    const response = await fetch(url, options);
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new Error(body?.message || `请求失败：${response.status}`);
    return body;
}

async function initialize() {
    try {
        const maps = await request(API);
        if (!maps.length) throw new Error('没有发现可用的 DISTRIBUTION 数据包');
        state.mapId = maps[0].mapId;
        const defaultSearch = maps[0].embeddingConfigured
            ? '哪里可以办会员'
            : '最近的咨询台在哪';
        const counts = maps[0].recordCounts;
        $('datasetSummary').textContent = `${state.mapId} · ${counts.entities} 实体 · ${counts.navigationNodes} 节点`;
        $('searchInput').placeholder = maps[0].embeddingConfigured
            ? '输入自然语言需求，例如：哪里可以办会员'
            : '输入名称或需求，例如：最近的咨询台在哪';
        state.floors = await request(`${API}/${state.mapId}/floors`);
        renderFloorTabs();
        const preferred = state.floors.find(floor => floor.name === 'F1') || state.floors[0];
        await selectFloor(preferred.name);
        bindEvents();
        setTimeout(() => runSearch(defaultSearch), 150);
    } catch (error) {
        $('mapLoading').textContent = error.message;
        toast(error.message);
    }
}

function bindEvents() {
    $('searchButton').addEventListener('click', () => runSearch($('searchInput').value));
    $('searchInput').addEventListener('keydown', event => {
        if (event.key === 'Enter') runSearch(event.currentTarget.value);
    });
    document.querySelectorAll('[data-keyword]').forEach(button => button.addEventListener('click', () => {
        $('searchInput').value = button.dataset.keyword;
        runSearch(button.dataset.keyword);
    }));
    $('pathToggle').addEventListener('change', draw);
    $('resetViewButton').addEventListener('click', fitView);
    $('setStartButton').addEventListener('click', () => setEndpoint('start', state.selected));
    $('setTargetButton').addEventListener('click', () => setEndpoint('target', state.selected));
    $('clearStart').addEventListener('click', () => setEndpoint('start', null));
    $('clearTarget').addEventListener('click', () => setEndpoint('target', null));
    $('routeButton').addEventListener('click', planRoute);
    window.addEventListener('resize', resizeCanvas);
    canvas.addEventListener('wheel', zoomCanvas, { passive: false });
    canvas.addEventListener('mousedown', startDrag);
    window.addEventListener('mousemove', dragCanvas);
    window.addEventListener('mouseup', endDrag);
    canvas.addEventListener('click', pickEntity);
}

function renderFloorTabs() {
    const root = $('floorTabs');
    root.textContent = '';
    state.floors.forEach(floor => {
        const button = document.createElement('button');
        button.textContent = floor.name;
        button.title = `${floor.alias || floor.name} · ${floor.entityCount} 个实体`;
        button.className = floor.name === state.floorName ? 'active' : '';
        button.addEventListener('click', () => selectFloor(floor.name));
        root.append(button);
    });
}

async function selectFloor(floorName) {
    state.floorName = floorName;
    renderFloorTabs();
    $('mapLoading').classList.remove('hidden');
    try {
        state.entities = await request(`${API}/${state.mapId}/entities?floorName=${encodeURIComponent(floorName)}&includePaths=true`);
        state.bounds = calculateBounds(state.entities);
        state.camera = null;
        resizeCanvas();
    } catch (error) {
        toast(error.message);
    } finally {
        $('mapLoading').classList.add('hidden');
    }
}

async function runSearch(keyword) {
    const value = keyword?.trim();
    if (!value) return;
    $('searchInput').value = value;
    const root = $('searchResults');
    root.className = 'result-list';
    root.textContent = '搜索中…';
    $('searchNotice').classList.add('hidden');
    try {
        const reference = state.start?.id ? `&referenceEntityId=${encodeURIComponent(state.start.id)}` : '';
        const payload = await request(`${API}/${state.mapId}/search?keyword=${encodeURIComponent(value)}&limit=25${reference}`);
        const results = Array.isArray(payload) ? payload : payload.results;
        if (payload.message) {
            $('searchNotice').textContent = payload.message;
            $('searchNotice').classList.remove('hidden');
        }
        renderResultList(root, results, result => showEntity(result.id));
        if (!results.length) root.textContent = '没有找到匹配的实体';
    } catch (error) {
        root.textContent = error.message;
    }
}

function renderResultList(root, results, handler, nearby = false) {
    root.textContent = '';
    results.forEach(result => {
        const button = document.createElement('button');
        button.className = nearby ? 'nearby-item' : 'result-item';
        const dot = document.createElement('span');
        dot.className = `entity-dot ${result.kind}`;
        const copy = document.createElement('span');
        copy.className = 'result-copy';
        const name = document.createElement('strong');
        name.textContent = result.name || result.subtype;
        const meta = document.createElement('small');
        meta.textContent = [result.floorName, result.typeLabel || result.kind].filter(Boolean).join(' · ');
        copy.append(name, meta);
        const tail = document.createElement('span');
        tail.className = nearby ? 'nearby-distance' : 'floor-chip';
        tail.textContent = nearby
            ? `${result.distance.toFixed(1)} m`
            : result.routeDistance != null ? `${result.routeDistance.toFixed(1)} m` : result.floorName;
        button.append(dot, copy, tail);
        button.addEventListener('click', () => handler(result));
        root.append(button);
    });
}

async function showEntity(entityId) {
    try {
        const entity = await request(`${API}/${state.mapId}/entities/${encodeURIComponent(entityId)}`);
        state.selected = entity;
        if (entity.floorName && entity.floorName !== state.floorName) await selectFloor(entity.floorName);
        $('detailPanel').classList.remove('hidden');
        $('detailName').textContent = displayName(entity);
        $('detailMeta').textContent = [entity.floorName, entity.kind, entity.semanticProperties?.typeLabel].filter(Boolean).join(' · ');
        draw();
        const nearby = await request(`${API}/${state.mapId}/entities/${encodeURIComponent(entityId)}/nearby?limit=8`);
        renderResultList($('nearbyResults'), nearby, result => showEntity(result.id), true);
    } catch (error) {
        toast(error.message);
    }
}

function setEndpoint(type, entity) {
    state[type] = entity;
    $(`${type}Name`).textContent = entity ? displayName(entity) : '请选择实体';
    $('routeButton').disabled = !(state.start && state.target);
    state.route = null;
    $('routeResult').classList.add('hidden');
    draw();
}

async function planRoute() {
    if (!state.start || !state.target) return;
    const button = $('routeButton');
    button.disabled = true;
    button.textContent = '正在计算…';
    try {
        state.route = await request(`${API}/${state.mapId}/routes`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ startEntityId: state.start.id, targetEntityId: state.target.id, mode: 'WALK' })
        });
        renderRoute();
        await selectFloor(state.route.floors[0]);
    } catch (error) {
        toast(error.message);
    } finally {
        button.disabled = false;
        button.textContent = '规划步行路线';
    }
}

function renderRoute() {
    const route = state.route;
    $('routeResult').classList.remove('hidden');
    $('routeTitle').textContent = `${route.startEntity.name} → ${route.targetEntity.name}`;
    $('routeDistance').textContent = `${route.walkLength.toFixed(1)} m`;
    $('routeFloors').textContent = route.floors.length > 1 ? `${route.floors.join(' → ')} · ${route.transferCount} 次跨层` : `${route.floors[0]} 同层`;
    const root = $('routeSteps');
    root.textContent = '';
    route.steps.forEach((step, index) => {
        const card = document.createElement('button');
        card.className = `step-card ${step.type}`;
        const meta = document.createElement('small');
        meta.textContent = `${index + 1} · ${step.floorName}${step.distance != null ? ` · ${step.distance.toFixed(1)} m` : ''}`;
        const copy = document.createElement('div');
        copy.textContent = step.instruction;
        card.append(meta, copy);
        card.addEventListener('click', () => selectFloor(step.floorName));
        root.append(card);
    });
    draw();
}

function resizeCanvas() {
    const rect = canvas.getBoundingClientRect();
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.round(rect.width * ratio));
    canvas.height = Math.max(1, Math.round(rect.height * ratio));
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    if (!state.camera) fitView(); else draw();
}

function fitView() {
    if (!state.bounds) return;
    const rect = canvas.getBoundingClientRect();
    const width = Math.max(1, state.bounds.maxX - state.bounds.minX);
    const height = Math.max(1, state.bounds.maxY - state.bounds.minY);
    state.camera = {
        x: (state.bounds.minX + state.bounds.maxX) / 2,
        y: (state.bounds.minY + state.bounds.maxY) / 2,
        scale: Math.min((rect.width - 90) / width, (rect.height - 90) / height)
    };
    draw();
}

function project(point) {
    const rect = canvas.getBoundingClientRect();
    return [(point[0] - state.camera.x) * state.camera.scale + rect.width / 2,
        rect.height / 2 - (point[1] - state.camera.y) * state.camera.scale];
}

function unproject(pixel) {
    const rect = canvas.getBoundingClientRect();
    return [(pixel[0] - rect.width / 2) / state.camera.scale + state.camera.x,
        (rect.height / 2 - pixel[1]) / state.camera.scale + state.camera.y];
}

function draw() {
    if (!state.camera) return;
    const rect = canvas.getBoundingClientRect();
    context.clearRect(0, 0, rect.width, rect.height);
    const order = { FLOOR: 0, SPACE: 1, NAVIGATION_PATH: 2, CONNECTOR: 3, FACILITY: 4 };
    [...state.entities].sort((a, b) => (order[a.kind] ?? 5) - (order[b.kind] ?? 5)).forEach(drawEntity);
    drawRoute();
    drawEndpoint(state.start, '#1f5143', '起');
    drawEndpoint(state.target, '#e25546', '终');
}

function drawEntity(entity) {
    if (entity.kind === 'NAVIGATION_PATH' && !$('pathToggle').checked) return;
    const selected = state.selected?.id === entity.id;
    const style = {
        FLOOR: { fill: '#f7f5ee', stroke: '#9daaa4', width: 1.3 },
        SPACE: { fill: selected ? '#f1dfb9' : '#e8e9e3', stroke: selected ? '#c49a54' : '#c7ceca', width: selected ? 2.2 : .8 },
        NAVIGATION_PATH: { fill: null, stroke: '#83ad9d', width: .8 },
        CONNECTOR: { fill: '#e4913e', stroke: '#a45e1d', width: 1 },
        FACILITY: { fill: '#2c7eb4', stroke: '#fff', width: 1.3 }
    }[entity.kind] || { fill: '#ccc', stroke: '#999', width: 1 };
    drawGeometry(entity.geometry, style);
    if (entity.labelPoint && entity.kind === 'SPACE' && state.camera.scale > 3) {
        const point = entity.labelPoint.coordinates;
        const pixel = project(point);
        context.fillStyle = '#4c5a54';
        context.font = '10px sans-serif';
        context.fillText(displayName(entity).slice(0, 12), pixel[0] + 4, pixel[1] - 4);
    }
}

function drawGeometry(geometry, style) {
    if (!geometry?.coordinates) return;
    context.lineWidth = style.width;
    context.strokeStyle = style.stroke;
    context.fillStyle = style.fill || 'transparent';
    const type = geometry.type;
    if (type === 'Point') {
        const p = project(geometry.coordinates);
        context.beginPath(); context.arc(p[0], p[1], 4.2, 0, Math.PI * 2);
        if (style.fill) context.fill(); context.stroke();
    } else if (type === 'LineString') {
        drawLine(geometry.coordinates, false, style);
    } else if (type === 'MultiLineString') {
        geometry.coordinates.forEach(line => drawLine(line, false, style));
    } else if (type === 'Polygon') {
        drawPolygon(geometry.coordinates, style);
    } else if (type === 'MultiPolygon') {
        geometry.coordinates.forEach(polygon => drawPolygon(polygon, style));
    }
}

function drawLine(coordinates, close, style) {
    if (!coordinates.length) return;
    context.beginPath();
    coordinates.forEach((point, index) => {
        const p = project(point);
        if (index === 0) context.moveTo(p[0], p[1]); else context.lineTo(p[0], p[1]);
    });
    if (close) context.closePath();
    if (close && style.fill) context.fill();
    context.stroke();
}

function drawPolygon(rings, style) {
    context.beginPath();
    rings.forEach(ring => ring.forEach((point, index) => {
        const p = project(point);
        if (index === 0) context.moveTo(p[0], p[1]); else context.lineTo(p[0], p[1]);
    }));
    context.closePath();
    if (style.fill) context.fill('evenodd');
    context.stroke();
}

function drawRoute() {
    if (!state.route) return;
    const routeStyle = { fill: null, stroke: '#e25546', width: 3.5 };
    state.route.edges.filter(edge => edge.geometry && nodeFloor(edge.fromNodeId) === state.floorName)
        .forEach(edge => drawGeometry(edge.geometry, routeStyle));
    context.save();
    context.setLineDash([6, 5]);
    state.route.accessLinks.filter(link => link.floorName === state.floorName)
        .forEach(link => drawLine([link.from, link.to], false, { fill: null, stroke: '#e25546', width: 2 }));
    context.restore();
}

function nodeFloor(nodeId) {
    const parts = nodeId.split(':');
    return parts.length > 3 ? parts[2] : null;
}

function drawEndpoint(entity, color, label) {
    if (!entity || entity.floorName !== state.floorName) return;
    const point = representativePoint(entity);
    if (!point) return;
    const p = project(point);
    context.beginPath(); context.arc(p[0], p[1], 10, 0, Math.PI * 2);
    context.fillStyle = color; context.fill();
    context.fillStyle = '#fff'; context.font = 'bold 10px sans-serif'; context.textAlign = 'center'; context.textBaseline = 'middle';
    context.fillText(label, p[0], p[1]); context.textAlign = 'start'; context.textBaseline = 'alphabetic';
}

function calculateBounds(entities) {
    const values = [];
    entities.filter(entity => entity.kind !== 'NAVIGATION_PATH').forEach(entity => collectCoordinates(entity.geometry?.coordinates, values));
    if (!values.length) entities.forEach(entity => collectCoordinates(entity.geometry?.coordinates, values));
    return values.reduce((b, p) => ({ minX: Math.min(b.minX, p[0]), minY: Math.min(b.minY, p[1]), maxX: Math.max(b.maxX, p[0]), maxY: Math.max(b.maxY, p[1]) }),
        { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity });
}

function collectCoordinates(value, output) {
    if (!Array.isArray(value)) return;
    if (value.length >= 2 && typeof value[0] === 'number' && typeof value[1] === 'number') output.push(value);
    else value.forEach(child => collectCoordinates(child, output));
}

function representativePoint(entity) {
    if (entity?.labelPoint?.type === 'Point') return entity.labelPoint.coordinates;
    if (entity?.geometry?.type === 'Point') return entity.geometry.coordinates;
    const coords = []; collectCoordinates(entity?.geometry?.coordinates, coords);
    if (!coords.length) return null;
    const bounds = coords.reduce((b, p) => ({ minX: Math.min(b.minX, p[0]), minY: Math.min(b.minY, p[1]), maxX: Math.max(b.maxX, p[0]), maxY: Math.max(b.maxY, p[1]) }),
        { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity });
    return [(bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2];
}

function zoomCanvas(event) {
    event.preventDefault();
    if (!state.camera) return;
    const rect = canvas.getBoundingClientRect();
    const pixel = [event.clientX - rect.left, event.clientY - rect.top];
    const before = unproject(pixel);
    const factor = event.deltaY < 0 ? 1.17 : 1 / 1.17;
    state.camera.scale = Math.max(.15, Math.min(80, state.camera.scale * factor));
    const after = unproject(pixel);
    state.camera.x += before[0] - after[0]; state.camera.y += before[1] - after[1];
    draw();
}

function startDrag(event) {
    state.dragging = false;
    state.dragPoint = [event.clientX, event.clientY];
    canvas.classList.add('dragging');
}
function dragCanvas(event) {
    if (!state.dragPoint || !state.camera) return;
    const dx = event.clientX - state.dragPoint[0], dy = event.clientY - state.dragPoint[1];
    if (Math.abs(dx) + Math.abs(dy) > 2) state.dragging = true;
    state.camera.x -= dx / state.camera.scale; state.camera.y += dy / state.camera.scale;
    state.dragPoint = [event.clientX, event.clientY]; draw();
}
function endDrag() { state.dragPoint = null; canvas.classList.remove('dragging'); }

function pickEntity(event) {
    if (state.dragging || !state.camera) return;
    const rect = canvas.getBoundingClientRect();
    const click = [event.clientX - rect.left, event.clientY - rect.top];
    const candidate = state.entities.filter(entity => ['SPACE', 'FACILITY', 'CONNECTOR'].includes(entity.kind))
        .map(entity => ({ entity, point: representativePoint(entity) })).filter(value => value.point)
        .map(value => { const p = project(value.point); return { entity: value.entity, distance: Math.hypot(p[0] - click[0], p[1] - click[1]) }; })
        .filter(value => value.distance < 18).sort((a, b) => a.distance - b.distance)[0];
    if (candidate) showEntity(candidate.entity.id);
}

function displayName(entity) { return entity?.name || entity?.subtype || entity?.id || '未命名实体'; }
function toast(message) {
    const element = $('toast'); element.textContent = message; element.classList.add('show');
    clearTimeout(toast.timer); toast.timer = setTimeout(() => element.classList.remove('show'), 3200);
}

initialize();
