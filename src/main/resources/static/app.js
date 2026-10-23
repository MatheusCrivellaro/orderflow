"use strict";

const TOKEN_KEY = "orderflow.token";
const PAYMENT_LIMIT = 5000;
const POLL_INTERVAL_MS = 1200;

const STOPS = ["Recebido", "Estoque", "Pagamento", "Confirmado"];
const STATUS_TEXT = {
    PENDING: "Reservando estoque",
    STOCK_RESERVED: "Processando pagamento",
    CONFIRMED: "Confirmado",
    CANCELLED: "Cancelado",
};

const brl = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });
const time = new Intl.DateTimeFormat("pt-BR", { hour: "2-digit", minute: "2-digit" });

const state = {
    token: null,
    user: null,
    authMode: "login",
    products: [],
    cart: new Map(),
    orders: [],
    pollTimer: null,
    noticeTimer: null,
};

const $ = (selector) => document.querySelector(selector);

// ---------- Utilitários ----------

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[char]));
}

function isFinal(status) {
    return status === "CONFIRMED" || status === "CANCELLED";
}

function notify(message, tone = "info") {
    const notice = $("#notice");
    notice.dataset.tone = tone;
    notice.textContent = message;
    clearTimeout(state.noticeTimer);
    state.noticeTimer = setTimeout(() => { notice.textContent = ""; }, 6000);
}

function setBusy(button, busy, busyText) {
    if (busy) {
        button.dataset.label = button.textContent;
        button.textContent = busyText;
        button.disabled = true;
    } else {
        button.textContent = button.dataset.label ?? button.textContent;
        button.disabled = false;
    }
}

function readStoredToken() {
    try {
        return localStorage.getItem(TOKEN_KEY);
    } catch {
        return null;
    }
}

function storeToken(token) {
    try {
        if (token) localStorage.setItem(TOKEN_KEY, token);
        else localStorage.removeItem(TOKEN_KEY);
    } catch {
        // Sem armazenamento disponível: a sessão vale só para esta aba
    }
}

function decodeToken(token) {
    try {
        const base64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
        const bytes = Uint8Array.from(atob(base64), (char) => char.charCodeAt(0));
        const claims = JSON.parse(new TextDecoder().decode(bytes));
        if (claims.exp * 1000 <= Date.now()) return null;
        return {
            id: Number(claims.sub),
            email: claims.email,
            admin: (claims.roles || []).includes("ADMIN"),
        };
    } catch {
        return null;
    }
}

// ---------- API ----------

async function api(path, { method = "GET", body } = {}) {
    const headers = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (state.token) headers.Authorization = `Bearer ${state.token}`;

    const response = await fetch(path, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });

    if (response.status === 401 && state.token) {
        logout("Sua sessão expirou. Entre novamente.");
        throw new Error("Sessão expirada");
    }

    const text = await response.text();
    const data = text ? JSON.parse(text) : null;

    if (!response.ok) {
        const fields = data?.errors ? Object.entries(data.errors).map(([field, msg]) => `${field} ${msg}`).join("; ") : "";
        const detail = data?.detail || `A requisição falhou (HTTP ${response.status}).`;
        throw new Error(fields ? `${detail}: ${fields}` : detail);
    }
    return data;
}

// ---------- Sessão ----------

function startSession(token) {
    const user = decodeToken(token);
    if (!user) {
        storeToken(null);
        showAuth();
        return;
    }
    state.token = token;
    state.user = user;
    storeToken(token);
    showApp();
}

function logout(message) {
    clearTimeout(state.pollTimer);
    state.token = null;
    state.user = null;
    state.cart.clear();
    state.orders = [];
    state.products = [];
    $("#order-list").innerHTML = "";
    storeToken(null);
    showAuth();
    if (message) notify(message, "error");
}

function showAuth() {
    $("#auth-view").hidden = false;
    $("#app-view").hidden = true;
    $("#session").hidden = true;
}

async function showApp() {
    $("#auth-view").hidden = true;
    $("#app-view").hidden = false;
    $("#session").hidden = false;
    $("#session-email").textContent = state.user.email;
    $("#session-role").hidden = !state.user.admin;
    $("#admin-panel").hidden = !state.user.admin;
    $("#orders-title").textContent = state.user.admin ? "Todos os pedidos" : "Seus pedidos";
    renderCart();

    try {
        await Promise.all([loadProducts(), loadOrders()]);
    } catch (error) {
        notify(error.message, "error");
    }
}

// ---------- Entrar / criar conta ----------

function setAuthMode(mode) {
    state.authMode = mode;
    const registering = mode === "register";
    document.querySelectorAll(".tab").forEach((tab) => {
        tab.setAttribute("aria-selected", String(tab.dataset.mode === mode));
    });
    $("#name-field").hidden = !registering;
    $("#name-field input").required = registering;
    $("#password-hint").hidden = !registering;
    $("#auth-form [name=password]").autocomplete = registering ? "new-password" : "current-password";
    $("#auth-submit").textContent = registering ? "Criar conta" : "Entrar";
    $("#auth-error").hidden = true;
}

async function onAuthSubmit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const error = $("#auth-error");
    const submit = $("#auth-submit");
    const registering = state.authMode === "register";

    if (!form.checkValidity()) {
        error.textContent = registering
            ? "Preencha nome, um e-mail válido e uma senha com pelo menos 6 caracteres."
            : "Preencha um e-mail válido e a senha.";
        error.hidden = false;
        return;
    }

    const data = Object.fromEntries(new FormData(form));
    error.hidden = true;
    setBusy(submit, true, registering ? "Criando conta…" : "Entrando…");

    try {
        if (registering) {
            await api("/auth/register", {
                method: "POST",
                body: { name: data.name, email: data.email, password: data.password },
            });
        }
        const token = await api("/auth/login", {
            method: "POST",
            body: { email: data.email, password: data.password },
        });
        form.reset();
        setAuthMode("login");
        startSession(token.accessToken);
    } catch (err) {
        error.textContent = err.message;
        error.hidden = false;
    } finally {
        setBusy(submit, false);
    }
}

// ---------- Produtos ----------

async function loadProducts() {
    const page = await api("/products?size=100&sort=id");
    state.products = page.content;
    renderProducts();
    renderCart();
}

function renderProducts() {
    const list = $("#product-list");
    if (state.products.length === 0) {
        list.innerHTML = `<li class="list-empty">Nenhum produto cadastrado.</li>`;
        return;
    }
    list.innerHTML = state.products.map((product) => {
        const available = product.stock - (state.cart.get(product.id) || 0);
        const stockText = product.stock === 0 ? "Esgotado" : `${product.stock} em estoque`;
        return `
            <li class="product">
                <div class="product-info">
                    <span class="product-name">${escapeHtml(product.name)}</span>
                    ${product.description ? `<span class="product-desc">${escapeHtml(product.description)}</span>` : ""}
                </div>
                <span class="product-stock${product.stock === 0 ? " is-out" : ""}">${stockText}</span>
                <span class="product-price">${brl.format(product.price)}</span>
                <button class="btn btn-secondary" type="button" data-add="${product.id}"
                        ${available <= 0 ? "disabled" : ""}
                        aria-label="Adicionar ${escapeHtml(product.name)} ao carrinho">Adicionar</button>
            </li>`;
    }).join("");
}

async function onProductSubmit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    if (!form.reportValidity()) return;

    const data = Object.fromEntries(new FormData(form));
    const submit = form.querySelector("button[type=submit]");
    setBusy(submit, true, "Cadastrando…");
    try {
        const product = await api("/products", {
            method: "POST",
            body: {
                name: data.name,
                description: data.description || null,
                price: Number(data.price),
                stock: Number(data.stock),
            },
        });
        form.reset();
        notify(`Produto "${product.name}" cadastrado.`);
        await loadProducts();
    } catch (err) {
        notify(err.message, "error");
    } finally {
        setBusy(submit, false);
    }
}

// ---------- Carrinho ----------

function changeCart(productId, delta) {
    const product = state.products.find((p) => p.id === productId);
    if (!product) return;
    const quantity = Math.min((state.cart.get(productId) || 0) + delta, product.stock, 100);
    if (quantity <= 0) state.cart.delete(productId);
    else state.cart.set(productId, quantity);
    renderProducts();
    renderCart();
}

function renderCart() {
    const lines = [...state.cart]
        .map(([id, quantity]) => ({ product: state.products.find((p) => p.id === id), quantity }))
        .filter((line) => line.product);

    $("#cart-list").innerHTML = lines.map(({ product, quantity }) => `
        <li class="cart-line">
            <span class="cart-name">${escapeHtml(product.name)}</span>
            <span class="cart-subtotal">${brl.format(product.price * quantity)}</span>
            <div class="stepper">
                <button type="button" data-dec="${product.id}" aria-label="Remover uma unidade de ${escapeHtml(product.name)}">−</button>
                <output>${quantity}</output>
                <button type="button" data-inc="${product.id}" ${quantity >= Math.min(product.stock, 100) ? "disabled" : ""}
                        aria-label="Adicionar uma unidade de ${escapeHtml(product.name)}">+</button>
            </div>
        </li>`).join("");

    const total = lines.reduce((sum, { product, quantity }) => sum + product.price * quantity, 0);
    $("#cart-total").textContent = brl.format(total);
    $("#cart-empty").hidden = lines.length > 0;
    $("#cart-warning").hidden = total <= PAYMENT_LIMIT;
    $("#place-order").disabled = lines.length === 0;
}

async function placeOrder() {
    const button = $("#place-order");
    const items = [...state.cart].map(([productId, quantity]) => ({ productId, quantity }));
    setBusy(button, true, "Enviando pedido…");
    try {
        const order = await api("/orders", { method: "POST", body: { items } });
        state.cart.clear();
        state.orders = [order, ...state.orders.filter((o) => o.id !== order.id)];
        renderProducts();
        renderOrders();
        schedulePoll();
        notify(`Pedido #${order.id} recebido. Acompanhe o andamento logo abaixo.`);
    } catch (err) {
        notify(err.message, "error");
    } finally {
        setBusy(button, false);
        renderCart();
    }
}

// ---------- Pedidos ----------

async function loadOrders() {
    const inProgress = new Set(state.orders.filter((o) => !isFinal(o.status)).map((o) => o.id));
    const page = await api("/orders?size=20");
    state.orders = page.content;
    renderOrders();

    // Quando um pedido termina, o estoque mudou (reserva ou devolução)
    const finished = state.orders.some((o) => inProgress.has(o.id) && isFinal(o.status));
    if (finished) await loadProducts();
    schedulePoll();
}

function schedulePoll() {
    clearTimeout(state.pollTimer);
    if (!state.token || !state.orders.some((o) => !isFinal(o.status))) return;
    state.pollTimer = setTimeout(() => {
        loadOrders().catch((err) => notify(err.message, "error"));
    }, POLL_INTERVAL_MS);
}

function routeFor(order) {
    switch (order.status) {
        case "PENDING":
            return { fill: 0, stops: ["done", "active", "todo", "todo"] };
        case "STOCK_RESERVED":
            return { fill: 1 / 3, stops: ["done", "done", "active", "todo"] };
        case "CONFIRMED":
            return { fill: 1, stops: ["done", "done", "done", "done"] };
        default: {
            const atPayment = /pagamento/i.test(order.failureReason || "");
            return atPayment
                ? { fill: 1 / 3, stops: ["done", "done", "failed", "todo"], failedLabel: "Recusado" }
                : { fill: 0, stops: ["done", "failed", "todo", "todo"], failedLabel: "Sem estoque" };
        }
    }
}

function createOrderElement(order) {
    const item = document.createElement("li");
    item.className = "order";
    item.dataset.id = order.id;
    item.innerHTML = `
        <div class="order-head">
            <h3 class="order-title">Pedido #${order.id}</h3>
            <span class="order-status"></span>
            <span class="order-amount"></span>
        </div>
        <div class="route" aria-hidden="true">
            <span class="route-fill"></span>
            <ol class="stops">
                ${STOPS.map((_, i) => `<li class="stop" style="--i:${i}"><span class="stop-label"></span></li>`).join("")}
            </ol>
        </div>
        <p class="order-meta"></p>
        <p class="order-reason" hidden></p>`;
    return item;
}

function updateOrderElement(item, order) {
    const route = routeFor(order);
    item.dataset.status = order.status;
    item.querySelector(".order-status").textContent = STATUS_TEXT[order.status] ?? order.status;
    item.querySelector(".order-amount").textContent = brl.format(order.totalAmount);
    item.querySelector(".route").style.setProperty("--fill", route.fill);

    item.querySelectorAll(".stop").forEach((stop, i) => {
        stop.dataset.state = route.stops[i];
        stop.querySelector(".stop-label").textContent = route.stops[i] === "failed" ? route.failedLabel : STOPS[i];
    });

    const units = order.items.reduce((sum, i) => sum + i.quantity, 0);
    const names = order.items.map((i) => `${i.quantity}x ${i.productName}`).join(", ");
    const owner = state.user?.admin ? `, cliente #${order.userId}` : "";
    item.querySelector(".order-meta").textContent =
        `${names} (${units} ${units === 1 ? "unidade" : "unidades"}), feito às ${time.format(new Date(order.createdAt))}${owner}`;

    const reason = item.querySelector(".order-reason");
    reason.hidden = !order.failureReason;
    reason.textContent = order.failureReason ?? "";
}

function renderOrders() {
    const list = $("#order-list");
    $("#orders-empty").hidden = state.orders.length > 0;

    // Atualiza os elementos existentes em vez de recriá-los, para a linha animar quando o status muda
    const existing = new Map([...list.children].map((el) => [el.dataset.id, el]));
    state.orders.forEach((order, index) => {
        const key = String(order.id);
        const item = existing.get(key) ?? createOrderElement(order);
        existing.delete(key);
        if (list.children[index] !== item) list.insertBefore(item, list.children[index] ?? null);
        updateOrderElement(item, order);
    });
    existing.forEach((el) => el.remove());
}

// ---------- Eventos ----------

document.querySelectorAll(".tab").forEach((tab) => {
    tab.addEventListener("click", () => setAuthMode(tab.dataset.mode));
});
$("#auth-form").addEventListener("submit", onAuthSubmit);
$("#product-form").addEventListener("submit", onProductSubmit);
$("#logout").addEventListener("click", () => logout());
$("#place-order").addEventListener("click", placeOrder);

$("#product-list").addEventListener("click", (event) => {
    const button = event.target.closest("[data-add]");
    if (button) changeCart(Number(button.dataset.add), 1);
});

$("#cart-list").addEventListener("click", (event) => {
    const inc = event.target.closest("[data-inc]");
    const dec = event.target.closest("[data-dec]");
    if (inc) changeCart(Number(inc.dataset.inc), 1);
    if (dec) changeCart(Number(dec.dataset.dec), -1);
});

// ---------- Início ----------

const storedToken = readStoredToken();
if (storedToken) startSession(storedToken);
else showAuth();
