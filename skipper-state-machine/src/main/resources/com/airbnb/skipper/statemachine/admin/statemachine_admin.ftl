<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Skipper State Machine Admin</title>
  <link href="https://bootswatch.com/4/darkly/bootstrap.min.css" rel="stylesheet">
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css">
  <link rel="stylesheet" href="https://unpkg.com/vis-timeline@7.7.3/styles/vis-timeline-graph2d.min.css">
  <style>
    /* ═══════════════════════════════════════════
       1. Custom Properties (Theme)
       ═══════════════════════════════════════════ */
    :root {
      /* Layout */
      --bg-body: #1a1a2e;
      --bg-card: #16213e;
      --bg-header: #0f3460;
      --bg-deep: #0d1b2a;
      --border-primary: #0f3460;
      --border-subtle: #1a2744;
      --border-header: #1a1a4e;
      /* Text */
      --text-primary: #e0e0e0;
      --text-secondary: #c0c8d0;
      --text-muted: #8899aa;
      --text-dim: #556677;
      /* Accent */
      --color-accent: #3498db;
      --color-accent-light: #7ec8f0;
      --color-danger: #e74c3c;
      /* Row 1: States */
      --color-state: #00bc8c;
      --color-state-border: #00a07a;
      --color-state-initial: #00bcd4;
      --color-state-initial-border: #00a0b4;
      --color-terminal: #6c757d;
      --color-terminal-border: #5a6268;
      /* Row 2: Transitions — envelopes */
      --color-transition-to: #00bc8c;
      --color-transition-to-bg: rgba(0,188,140,0.3);
      --color-transition-stay: #3498db;
      --color-transition-stay-bg: rgba(52,152,219,0.3);
      --color-transition-ignore: #95a5a6;
      --color-transition-ignore-bg: rgba(149,165,166,0.3);
      --color-transition-ignore-text: #bdc3c7;
      --color-transition-guard: #f39c12;
      --color-transition-guard-bg: rgba(243,156,18,0.3);
      --color-transition-guard-text: #f5c96a;
      --color-transition-invalid: #dc3545;
      --color-transition-invalid-bg: rgba(220,53,69,0.3);
      --color-transition-invalid-text: #f1948a;
      /* Row 2: Transitions — event points */
      --color-event-handled: #3498db;
      --color-event-handled-text: #7ec8f0;
      --color-event-unhandled: #dc3545;
      --color-event-unhandled-text: #f1948a;
      --color-event-guarded: #f39c12;
      --color-event-guarded-text: #f5c96a;
      /* Row 2: Transitions — timers */
      --color-after: #ffc107;
      --color-after-text: #ffe082;
      --color-timeout: #ff5722;
      --color-timeout-text: #ff8a65;
      /* Row 3: Transition details — middleware */
      --color-mw-before: #3f51b5;
      --color-mw-before-border: #303f9f;
      --color-mw-after: #9b59b6;
      --color-mw-after-border: #844ea0;
      --color-mw-terminal: #546e7a;
      --color-mw-terminal-border: #455a64;
      --color-mw-invalid: #795548;
      --color-mw-invalid-border: #5d4037;
      /* Row 3: Transition details — hooks */
      --color-hook-exit: #e67e73;
      --color-hook-exit-border: #d4645a;
      --color-hook-entry: #2e8b57;
      --color-hook-entry-border: #267349;
      /* Row 3: Transition details — handlers */
      --color-handler-event: #e91e63;
      --color-handler-event-border: #c2185b;
      --color-handler-timeout: #ff9800;
      --color-handler-timeout-border: #e65100;
      --color-handler-after: #cddc39;
      --color-handler-after-border: #afb42b;
      /* Row 4: Side effects */
      --color-action: #8e44ad;
      --color-action-border: #7d3c98;
      --color-action-fail: #c0392b;
      --color-action-fail-border: #a93226;
      --color-action-comp: #d35400;
      --color-action-comp-border: #ba4a00;
    }

    /* ═══════════════════════════════════════════
       2. Base & Layout
       ═══════════════════════════════════════════ */
    body { background: var(--bg-body); color: var(--text-primary); }
    .card { background: var(--bg-card); border: 1px solid var(--border-primary); }
    .card-header { background: var(--bg-header); border-bottom: 1px solid var(--border-header); }
    .badge-state { font-size: 0.9em; padding: 4px 10px; }
    .meta-label { color: var(--text-muted); font-size: 0.85em; text-transform: uppercase; }
    .meta-value { font-size: 1em; font-weight: 500; }
    .text-muted-custom { color: var(--text-muted); }
    .text-dim { color: var(--text-dim); }
    .text-error { color: var(--color-danger); }
    .icon-sm { font-size: 0.7em; }
    .btn-copy { cursor: pointer; opacity: 0.6; }
    .btn-copy:hover { opacity: 1; }

    /* ═══════════════════════════════════════════
       3. Navigation
       ═══════════════════════════════════════════ */
    .navbar-sm-admin { background: var(--bg-header); }
    .nav-right-spacer { margin-right: 15px; }
    .nav-search-input {
      background: var(--bg-card);
      border-color: var(--border-subtle);
      color: var(--text-primary);
      width: 300px;
    }
    .skipper-link { font-size: 0.85em; }
    .nav-tabs { border-bottom-color: var(--border-primary); }
    .nav-tabs .nav-link { color: var(--text-muted); border: none; }
    .nav-tabs .nav-link.active { background: transparent; color: var(--color-accent); border-bottom: 2px solid var(--color-accent); }
    .nav-tabs .nav-link:hover { color: var(--text-primary); border-color: transparent; }

    /* ═══════════════════════════════════════════
       4. vis-timeline Dark Theme
       ═══════════════════════════════════════════ */
    .vis-timeline { border: 1px solid var(--border-primary); background: var(--bg-card); font-family: inherit; color: var(--text-primary); }
    .vis-panel.vis-center, .vis-panel.vis-left, .vis-panel.vis-right,
    .vis-panel.vis-top, .vis-panel.vis-bottom { border-color: var(--border-primary); }
    .vis-time-axis .vis-text { color: var(--text-muted); font-size: 11px; }
    .vis-time-axis .vis-grid.vis-minor { border-color: var(--border-subtle); }
    .vis-time-axis .vis-grid.vis-major { border-color: var(--border-primary); }
    .vis-labelset .vis-label { color: var(--text-secondary); font-weight: 600; font-size: 12px; border-bottom: 1px solid var(--border-primary); }
    .vis-foreground .vis-group { border-bottom: 1px solid var(--border-primary); }
    .vis-current-time { background-color: var(--color-danger); width: 2px; }

    /* ═══════════════════════════════════════════
       5. Timeline Item Types
       ═══════════════════════════════════════════ */
    /* All range items stretch to fill the full group row height */
    .vis-item.vis-range { top: 0 !important; height: 100% !important; }
    /* Alternate brightness on even items for readability when consecutive items share a color */
    .vis-item.vis-range.tl-alt { filter: brightness(1.25); }
    .vis-item.vis-range.tl-state { background: var(--color-state); border-color: var(--color-state-border); color: #fff; font-weight: 600; font-size: 12px; border-radius: 4px; }
    .vis-item.vis-range.tl-state .vis-item-content { padding: 2px 8px; }
    .vis-item.vis-range.tl-state-terminal { background: var(--color-terminal); border-color: var(--color-terminal-border); }
    .vis-item.vis-point.tl-event .vis-dot { border-color: var(--color-accent); border-width: 3px; }
    .vis-item.vis-point.tl-event .vis-item-content { color: var(--color-accent-light); font-size: 12px; font-weight: 500; }
    .vis-item.vis-range.tl-action { background: var(--color-action); border-color: var(--color-action-border); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-action .vis-item-content { padding: 1px 6px; }
    .vis-item.vis-range.tl-action-fail { background: var(--color-action-fail); border-color: var(--color-action-fail-border); }
    .vis-item.vis-range.tl-action-comp { background: var(--color-action-comp); border-color: var(--color-action-comp-border); }
    .vis-item.vis-point.tl-after .vis-dot { border-color: var(--color-after); border-width: 3px; }
    .vis-item.vis-point.tl-after .vis-item-content { color: var(--color-after-text); font-size: 12px; }
    .vis-item.vis-point.tl-timeout .vis-dot { border-color: var(--color-timeout); border-width: 3px; }
    .vis-item.vis-point.tl-timeout .vis-item-content { color: var(--color-timeout-text); font-size: 12px; }
    .vis-item.vis-point.tl-pending .vis-dot { border-color: var(--text-muted); border-width: 2px; border-style: dashed; }
    .vis-item.vis-point.tl-pending .vis-item-content { color: var(--text-dim); font-size: 11px; font-style: italic; }

    /* ── Row 1: States (extended) ── */
    .vis-item.vis-range.tl-state-initial { background: var(--color-state-initial); border-color: var(--color-state-initial-border); color: #fff; font-weight: 600; font-size: 12px; border-radius: 4px; }
    .vis-item.vis-range.tl-state-initial .vis-item-content { padding: 2px 8px; }

    /* ── Row 2: Transitions ── */
    .vis-item.vis-range.tl-transition-to { background: var(--color-transition-to-bg); border: 1px solid var(--color-transition-to); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-transition-stay { background: var(--color-transition-stay-bg); border: 1px solid var(--color-transition-stay); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-transition-ignore { background: var(--color-transition-ignore-bg); border: 1px dashed var(--color-transition-ignore); color: var(--color-transition-ignore-text); font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-transition-guard { background: var(--color-transition-guard-bg); border: 1px dashed var(--color-transition-guard); color: var(--color-transition-guard-text); font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-transition-invalid { background: var(--color-transition-invalid-bg); border: 1px dashed var(--color-transition-invalid); color: var(--color-transition-invalid-text); font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-transition-to .vis-item-content,
    .vis-item.vis-range.tl-transition-stay .vis-item-content,
    .vis-item.vis-range.tl-transition-ignore .vis-item-content,
    .vis-item.vis-range.tl-transition-guard .vis-item-content,
    .vis-item.vis-range.tl-transition-invalid .vis-item-content { padding: 1px 6px; }

    .vis-item.vis-point.tl-event-handled .vis-dot { border-color: var(--color-event-handled); border-width: 3px; }
    .vis-item.vis-point.tl-event-handled .vis-item-content { color: var(--color-event-handled-text); font-size: 12px; font-weight: 500; }
    .vis-item.vis-point.tl-event-unhandled .vis-dot { border-color: var(--color-event-unhandled); border-width: 3px; }
    .vis-item.vis-point.tl-event-unhandled .vis-item-content { color: var(--color-event-unhandled-text); font-size: 12px; font-weight: 500; }
    .vis-item.vis-point.tl-event-guarded .vis-dot { border-color: var(--color-event-guarded); border-width: 3px; }
    .vis-item.vis-point.tl-event-guarded .vis-item-content { color: var(--color-event-guarded-text); font-size: 12px; font-weight: 500; }

    /* ── Row 3: Transition Details ── */
    .vis-item.vis-range.tl-mw-before { background: var(--color-mw-before); border-color: var(--color-mw-before-border); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-hook-exit { background: var(--color-hook-exit); border-color: var(--color-hook-exit-border); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-hook-entry { background: var(--color-hook-entry); border-color: var(--color-hook-entry-border); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-mw-after { background: var(--color-mw-after); border-color: var(--color-mw-after-border); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-mw-terminal { background: var(--color-mw-terminal); border-color: var(--color-mw-terminal-border); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-mw-invalid { background: var(--color-mw-invalid); border-color: var(--color-mw-invalid-border); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-mw-before .vis-item-content,
    .vis-item.vis-range.tl-hook-exit .vis-item-content,
    .vis-item.vis-range.tl-hook-entry .vis-item-content,
    .vis-item.vis-range.tl-mw-after .vis-item-content,
    .vis-item.vis-range.tl-mw-terminal .vis-item-content,
    .vis-item.vis-range.tl-mw-invalid .vis-item-content { padding: 1px 6px; }
    .vis-item.vis-range.tl-handler-event { background: var(--color-handler-event); border-color: var(--color-handler-event-border); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-handler-timeout { background: var(--color-handler-timeout); border-color: var(--color-handler-timeout-border); color: #fff; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-handler-after { background: var(--color-handler-after); border-color: var(--color-handler-after-border); color: #333; font-size: 11px; border-radius: 3px; }
    .vis-item.vis-range.tl-handler-event .vis-item-content,
    .vis-item.vis-range.tl-handler-timeout .vis-item-content,
    .vis-item.vis-range.tl-handler-after .vis-item-content { padding: 1px 6px; }

    /* ═══════════════════════════════════════════
       6. Detail Panel
       ═══════════════════════════════════════════ */
    .detail-panel { background: var(--bg-deep); border: 1px solid var(--border-primary); border-radius: 6px; padding: 12px 16px; margin-top: 8px; font-size: 0.9em; }
    .detail-panel pre { color: var(--text-primary); background: var(--bg-card); padding: 8px; border-radius: 4px; max-height: 200px; overflow: auto; margin: 4px 0; white-space: pre-wrap; word-break: break-all; }
    .detail-panel .detail-label { color: var(--text-muted); font-size: 0.85em; text-transform: uppercase; }

    /* ═══════════════════════════════════════════
       7. Mermaid
       ═══════════════════════════════════════════ */
    .mermaid-container { background: var(--bg-card); border: 1px solid var(--border-primary); border-radius: 6px; padding: 20px; overflow-x: auto; }
    .mermaid-container svg { max-width: 100%; }

    /* ═══════════════════════════════════════════
       8. Cards & Tables
       ═══════════════════════════════════════════ */
    .badge-purple { color: #fff; background-color: var(--color-action); }
    a.badge-purple:focus, a.badge-purple:hover { color: #fff; background-color: var(--color-action-border); }
    a.badge-purple.focus, a.badge-purple:focus { outline: 0; box-shadow: 0 0 0 .2rem rgba(142,68,173,.5); }
    .table-dark-custom { background: var(--bg-card); }
    .table-dark-custom th { background: var(--bg-header); border-color: var(--border-subtle); color: var(--text-secondary); }
    .table-dark-custom td { border-color: var(--border-subtle); color: var(--text-primary); }
    .table-dark-custom tbody tr:hover { background: var(--border-subtle); }

    /* ═══════════════════════════════════════════
       9. Expandable JSON
       ═══════════════════════════════════════════ */
    .json-toggle { cursor: pointer; color: var(--color-accent); font-size: 0.85em; }
    .json-toggle:hover { text-decoration: underline; }
    .json-detail { display: none; background: var(--bg-deep); border: 1px solid var(--border-subtle); border-radius: 4px; padding: 8px; margin-top: 4px; font-size: 0.85em; white-space: pre-wrap; word-break: break-all; max-height: 200px; overflow: auto; }

    /* ═══════════════════════════════════════════
       10. Search Hero
       ═══════════════════════════════════════════ */
    .search-hero { text-align: center; padding: 80px 20px; max-width: 600px; margin: 0 auto; }
    .search-hero h2 { color: var(--text-secondary); margin-bottom: 20px; }
    .search-hero .form-control { background: var(--bg-card); border-color: var(--border-primary); color: var(--text-primary); font-size: 1.2em; padding: 10px 16px; }
    .search-hero .form-control::placeholder { color: var(--text-dim); }

    /* ═══════════════════════════════════════════
       11. Legend
       ═══════════════════════════════════════════ */
    .legend { display: flex; gap: 16px; padding: 6px 12px; font-size: 0.85em; color: var(--text-muted); flex-wrap: wrap; align-items: center; }
    .legend label { cursor: pointer; margin: 0; display: flex; align-items: center; gap: 4px; }
    .legend label input { margin: 0; cursor: pointer; }
    .legend-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; vertical-align: middle; }

    /* ═══════════════════════════════════════════
       12. Signal Panel
       ═══════════════════════════════════════════ */
    .signal-panel { background: var(--bg-deep); border: 1px solid var(--border-primary); border-radius: 6px; padding: 16px; }
    .signal-panel select, .signal-panel textarea { background: var(--bg-card); border-color: var(--border-subtle); color: var(--text-primary); }
    .signal-panel .form-text { color: var(--text-muted); }
    .signal-result-spacer { margin-left: 10px; }
  </style>
</head>
<body>
<nav class="navbar navbar-expand-lg navbar-dark navbar-sm-admin">
  <div class="container-fluid">
    <a class="navbar-brand" href="#" onclick="showSearch(); return false;">
      <i class="fas fa-cogs"></i> Skipper SM Admin
    </a>
    <div class="collapse navbar-collapse">
      <ul class="navbar-nav ml-auto nav-right-spacer">
        <li class="nav-item">
          <a class="nav-link skipper-link" href="/skipper/admin/" target="_blank">
            View in Skipper Admin <i class="fas fa-external-link-alt"></i>
          </a>
        </li>
      </ul>
      <form class="form-inline" onsubmit="searchWorkflow(); return false;">
        <input class="form-control mr-2 nav-search-input" type="text" id="nav-search"
               placeholder="Workflow ID">
        <button class="btn btn-outline-info" type="submit"><i class="fas fa-search"></i></button>
      </form>
    </div>
  </div>
</nav>

<main class="container-fluid px-4 py-3">
  <div id="search-view" class="search-hero">
    <h2><i class="fas fa-cogs"></i> State Machine Admin</h2>
    <p class="text-muted-custom">Look up a state machine workflow by its ID to view the timeline, sequence diagram, and history.</p>
    <form role="search" onsubmit="searchWorkflow(); return false;">
      <div class="input-group">
        <input class="form-control" type="text" id="hero-search" placeholder="Enter Workflow ID..." autofocus>
        <div class="input-group-append">
          <button class="btn btn-info" type="submit"><i class="fas fa-search"></i> Search</button>
        </div>
      </div>
    </form>
  </div>

  <div id="instance-view" style="display:none;">
    <div id="instance-header" class="mb-3"></div>
    <ul class="nav nav-tabs mb-3" id="main-tabs" aria-label="Main navigation tabs">
      <li class="nav-item"><a class="nav-link active" href="#" onclick="showTab('timeline'); return false;"><i class="fas fa-stream"></i> Timeline</a></li>
      <li class="nav-item"><a class="nav-link" href="#" onclick="showTab('sequence'); return false;"><i class="fas fa-project-diagram"></i> Sequence Diagram</a></li>
      <li class="nav-item"><a class="nav-link" href="#" onclick="showTab('tables'); return false;"><i class="fas fa-table"></i> History Tables</a></li>
      <li class="nav-item"><a class="nav-link" href="#" onclick="showTab('signal'); return false;"><i class="fas fa-paper-plane"></i> Send Event</a></li>
    </ul>
    <div id="tab-timeline"></div>
    <div id="tab-sequence" style="display:none;"></div>
    <div id="tab-tables" style="display:none;"></div>
    <div id="tab-signal" style="display:none;"></div>
  </div>

  <div id="error-view" style="display:none;" class="text-center py-5">
    <h3 id="error-message" class="text-error"></h3>
    <button class="btn btn-outline-info mt-3" onclick="showSearch();">Back to Search</button>
  </div>
</main>

<script src="https://code.jquery.com/jquery-3.6.0.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@4.6.2/dist/js/bootstrap.bundle.min.js"></script>
<script src="https://unpkg.com/vis-timeline@7.7.3/standalone/umd/vis-timeline-graph2d.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
<script>
//# sourceURL=skipper-statemachine-admin.js
(function() {
  'use strict';

  // ═══════════════════════════════════════════════
  //  Constants
  // ═══════════════════════════════════════════════

  const POLL_INTERVAL_MS       = 2500;
  const MIN_BUFFER_MS          = 30000;
  const MAX_BUFFER_MS          = 3600000;
  const BUFFER_RATIO           = 0.05;
  const ZOOM_MAX_RATIO         = 1.1;
  const ZOOM_STEP              = 0.3;
  const MIN_VISUAL_RANGE_MS    = 1;
  const RELOAD_AFTER_SIGNAL_MS = 1500;
  const JSON_PREVIEW_MAX_CHARS = 60;
  const INPUT_PREVIEW_MAX_CHARS = 80;
  const ZOOM_MIN_MS            = 50;
  const ROUTE_PREFIX           = 'workflow/';
  const ADMIN_BASE_PATH        = window.location.pathname.replace(/\/$/, '');
  const API_BASE               = ADMIN_BASE_PATH + '/api/workflows/';

  const TERMINAL_STATUSES = Object.freeze(
    ['COMPLETED', 'ERROR', 'TIMEOUT', 'CANCELLED', 'COMPENSATION_COMPLETED']
  );

  const STATUS_BADGE_MAP = Object.freeze({
    RUNNING: 'success',
    COMPLETED: 'primary',
    WAITING: 'info',
    ERROR: 'danger',
    TRANSIENT_ERROR: 'warning',
    RETRIES_EXHAUSTED: 'danger',
    CANCELLED: 'secondary',
    TIMEOUT: 'warning',
    CREATED: 'info'
  });

  const TAB_NAMES = ['timeline', 'sequence', 'tables', 'signal'];

  const FILTER_ITEMS = [
    { type: 'states',       color: '#00bc8c', label: 'States' },
    { type: 'transitions',  color: '#3498db', label: 'Transitions' },
    { type: 'details',      color: '#3f51b5', label: 'Details' },
    { type: 'side-effects', color: '#8e44ad', label: 'Side Effects' },
    { type: 'pending',      color: '#8899aa', label: 'Pending', dashed: true, unchecked: true }
  ];

  const OUTCOME_BADGE_MAP = Object.freeze({
    'TRANSITION_TO': 'success',
    'STAY': 'info',
    'IGNORE_EXPLICIT': 'secondary',
    'IGNORE_GUARD': 'warning',
    'INVALID_NO_HANDLER': 'danger'
  });

  const OUTCOME_LABEL_MAP = Object.freeze({
    'TRANSITION_TO': 'Transitioned',
    'STAY': 'Stayed',
    'IGNORE_EXPLICIT': 'Ignored',
    'IGNORE_GUARD': 'Guard Rejected',
    'INVALID_NO_HANDLER': 'No Handler'
  });

  const TYPE_DEFAULTS = [
    { patterns: ['int', 'long', 'short', 'byte'], value: 0 },
    { patterns: ['float', 'double', 'decimal'],   value: 0.0 },
    { patterns: ['bool'],                          value: false },
    { patterns: ['string'],                        value: '' },
    { patterns: ['list', 'array', 'set'],          value: [] },
    { patterns: ['map'],                           value: {} }
  ];

  // ═══════════════════════════════════════════════
  //  Mutable State
  // ═══════════════════════════════════════════════

  let timeline = null;
  let currentData = null;
  let timelineItemMeta = {};
  let allTimelineItems = null;
  let filteredView = null;
  let timelineGroups = null;
  let pollInterval = null;
  let eventSchemas = {};
  let jsonToggleId = 0;
  let showChildren = false;

  // ═══════════════════════════════════════════════
  //  Utility Functions
  // ═══════════════════════════════════════════════

  /** Escapes a string for safe insertion into HTML content. */
  function escHtml(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /** Escapes a string for safe insertion into an HTML attribute value. */
  function escAttr(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/'/g, '&#39;')
      .replace(/"/g, '&quot;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  /** Escapes a string as a JavaScript string literal inside an HTML attribute. */
  function jsStringAttr(s) {
    return escAttr(JSON.stringify(String(s)));
  }

  /** Copies text to the clipboard with a fallback for older browsers. */
  function copyText(text) {
    if (navigator.clipboard) {
      navigator.clipboard.writeText(text).catch(function() {
        fallbackCopy(text);
      });
    } else {
      fallbackCopy(text);
    }
  }

  function fallbackCopy(text) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;opacity:0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
  }

  /** Formats a timestamp (epoch ms or ISO string) as "YYYY-MM-DD HH:MM:SS UTC". */
  function formatDate(input) {
    const d = new Date(input);
    if (isNaN(d.getTime())) return String(input);
    return d.toISOString().replace('T', ' ').replace(/\.\d+Z$/, ' UTC');
  }

  /**
   * Formats a PreciseTimestamp as "YYYY-MM-DD HH:MM:SS.mmm UTC" with sub-second precision.
   * Shows microseconds/nanoseconds only when non-zero.
   */
  function formatPreciseDate(pt) {
    if (!pt) return '\u2014';
    var d = new Date(pt.epoch_second * 1000 + Math.floor(pt.nano / 1000000));
    var base = d.toISOString().replace('T', ' ').replace(/\.\d+Z$/, '');
    var ms = Math.floor(pt.nano / 1000000) % 1000;
    var us = Math.floor(pt.nano / 1000) % 1000;
    var ns = pt.nano % 1000;
    var sub = String(ms).padStart(3, '0');
    if (us > 0 || ns > 0) sub += '.' + String(us).padStart(3, '0');
    if (ns > 0) sub += '.' + String(ns).padStart(3, '0');
    return base + '.' + sub + ' UTC';
  }

  /** Formats a duration in milliseconds as a human-readable string (e.g., "2h 15m 30s"). */
  function formatDuration(ms) {
    if (ms < 0) return '';
    const seconds = Math.floor(ms / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const days = Math.floor(hours / 24);

    if (days > 0)    return days + 'd ' + (hours % 24) + 'h ' + (minutes % 60) + 'm';
    if (hours > 0)   return hours + 'h ' + (minutes % 60) + 'm ' + (seconds % 60) + 's';
    if (minutes > 0) return minutes + 'm ' + (seconds % 60) + 's';
    if (seconds > 0) return seconds + 's';
    return ms + 'ms';
  }

  /** Formats a duration in nanoseconds as a human-readable string with sub-ms precision. */
  function formatNanoDuration(nanos) {
    if (nanos < 0) return '';
    var micros = Math.floor(nanos / 1000);
    var ms = Math.floor(nanos / 1000000);
    var seconds = Math.floor(ms / 1000);
    var minutes = Math.floor(seconds / 60);
    var hours = Math.floor(minutes / 60);
    var days = Math.floor(hours / 24);
    if (days > 0)    return days + 'd ' + (hours % 24) + 'h ' + (minutes % 60) + 'm';
    if (hours > 0)   return hours + 'h ' + (minutes % 60) + 'm ' + (seconds % 60) + 's';
    if (minutes > 0) return minutes + 'm ' + (seconds % 60) + 's';
    if (seconds > 0) return seconds + 's';
    if (ms > 0)      return ms + 'ms';
    if (micros > 0)  return micros + '\u00b5s';
    return nanos + 'ns';
  }

  /** Converts a PreciseTimestamp {epoch_second, nano} to a JS Date (millis precision). */
  function toDate(pt) {
    return new Date(pt.epoch_second * 1000 + Math.floor(pt.nano / 1000000));
  }

  /** Computes the duration in nanoseconds between two PreciseTimestamp objects. */
  function durationNanosBetween(a, b) {
    return (b.epoch_second - a.epoch_second) * 1000000000 + (b.nano - a.nano);
  }

  /** Centers a too-short range around its midpoint to reach MIN_VISUAL_RANGE_MS. Returns {start, end} in ms. */
  function ensureMinRange(startMs, endMs) {
    if (endMs - startMs >= MIN_VISUAL_RANGE_MS) return { start: startMs, end: endMs };
    var mid = (startMs + endMs) / 2;
    return { start: mid - MIN_VISUAL_RANGE_MS / 2, end: mid + MIN_VISUAL_RANGE_MS / 2 };
  }

  /** Returns true if the given status represents a terminal workflow state. */
  function isTerminalStatus(status) {
    return TERMINAL_STATUSES.indexOf(status) >= 0;
  }

  /** Returns a sensible default value for a Java/Kotlin type name. */
  function defaultForType(typeName) {
    const t = (typeName || '').toLowerCase();
    for (const entry of TYPE_DEFAULTS) {
      if (entry.patterns.some(function(p) { return t.indexOf(p) >= 0; })) {
        return entry.value;
      }
    }
    return null;
  }

  /** Returns the Bootstrap badge class for a workflow status. */
  function statusBadgeClass(status) {
    return STATUS_BADGE_MAP[status] || 'light';
  }

  /**
   * Renders a JSON value as a collapsible preview/detail pair.
   * Returns an HTML string with a clickable preview that expands to show full JSON.
   */
  function jsonCell(obj) {
    if (obj == null) return '<span class="text-dim">null</span>';
    const id = 'json-' + (jsonToggleId++);
    const pretty = JSON.stringify(obj, null, 2);
    const raw = JSON.stringify(obj);
    const preview = raw.substring(0, JSON_PREVIEW_MAX_CHARS);
    const truncated = raw.length > JSON_PREVIEW_MAX_CHARS ? '...' : '';
    return '<span class="json-toggle" onclick="$(\'#' + id + '\').toggle();">'
      + escHtml(preview) + truncated
      + ' <i class="fas fa-chevron-down icon-sm"></i></span>'
      + '<div class="json-detail" id="' + id + '">' + escHtml(pretty) + '</div>';
  }

  /**
   * Quotes bare integer values in JSON text that exceed Number.MAX_SAFE_INTEGER
   * to prevent precision loss during JSON.parse.
   */
  function safeguardLargeIntegers(text) {
    return text.replace(
      /:\s*(\d{16,})\s*([,\}\]])/g,
      function(match, num, suffix) {
        return Number(num) > Number.MAX_SAFE_INTEGER
          ? ':"' + num + '"' + suffix
          : match;
      }
    );
  }

  /** Fetches a workflow by ID as text, safeguards large integers, then parses JSON. */
  function fetchWorkflowJson(id) {
    return $.ajax({
      url: API_BASE + encodeURIComponent(id) + '?includeChildren=true',
      dataType: 'text'
    }).then(function(text) {
      return JSON.parse(safeguardLargeIntegers(text));
    });
  }

  // ═══════════════════════════════════════════════
  //  Navigation & Routing
  // ═══════════════════════════════════════════════

  /** Navigates to the workflow specified in the search input. */
  function searchWorkflow() {
    const id = $('#nav-search').val() || $('#hero-search').val();
    if (!id || !id.trim()) return;
    window.location.hash = ROUTE_PREFIX + id.trim();
  }

  /** Stops the auto-poll interval for running workflows. */
  function stopPolling() {
    if (pollInterval) {
      clearInterval(pollInterval);
      pollInterval = null;
    }
  }

  /** Returns to the search view, destroying the timeline and stopping polling. */
  function showSearch() {
    window.location.hash = '';
    stopPolling();
    $('#search-view').show();
    $('#instance-view').hide();
    $('#error-view').hide();
    if (timeline) {
      timeline.destroy();
      timeline = null;
    }
  }

  /** Displays an error message and hides other views. */
  function showError(msg) {
    $('#search-view').hide();
    $('#instance-view').hide();
    $('#error-view').show();
    $('#error-message').text(msg);
  }

  /** Switches to the specified tab (timeline, sequence, tables, signal). */
  function showTab(name) {
    TAB_NAMES.forEach(function(t) {
      $('#tab-' + t).toggle(t === name);
    });
    $('#main-tabs .nav-link').removeClass('active');
    const tabIndex = TAB_NAMES.indexOf(name);
    $('#main-tabs .nav-link').eq(tabIndex >= 0 ? tabIndex : 0).addClass('active');
    if (name === 'timeline' && timeline) {
      setTimelineToRealBounds(false);
    }
  }

  /** Loads a workflow by ID from the API and renders the instance view. */
  function loadWorkflow(id) {
    $('#search-view').hide();
    $('#error-view').hide();
    $('#instance-view').hide();
    fetchWorkflowJson(id)
      .done(function(data) {
        currentData = data;
        renderInstance(data);
      })
      .fail(function(xhr) {
        let msg = 'Workflow not found: ' + id;
        try { msg = JSON.parse(xhr.responseText).error || msg; } catch (e) { /* use default */ }
        showError(msg);
      });
  }

  /** Reads the URL hash and navigates to the appropriate view. */
  function route() {
    const hash = window.location.hash.substring(1);
    if (hash.startsWith(ROUTE_PREFIX)) {
      const id = decodeURIComponent(hash.substring(ROUTE_PREFIX.length));
      $('#nav-search').val(id);
      loadWorkflow(id);
    } else {
      showSearch();
    }
  }

  // ═══════════════════════════════════════════════
  //  Header
  // ═══════════════════════════════════════════════

  /** Renders the workflow instance header card. */
  function renderHeader(data) {
    $('#instance-header').html(buildHeaderCard(data));
  }

  const workflowJsonPanelState = {};

  function workflowJsonPanelKey(workflowId, panelName) {
    return workflowId + ':' + panelName;
  }

  function isWorkflowJsonPanelExpanded(workflowId, panelName) {
    return workflowJsonPanelState[workflowJsonPanelKey(workflowId, panelName)] === true;
  }

  function toggleWorkflowJsonPanel(workflowId, panelName) {
    const key = workflowJsonPanelKey(workflowId, panelName);
    const expanded = !isWorkflowJsonPanelExpanded(workflowId, panelName);
    workflowJsonPanelState[key] = expanded;
    $('#wf-' + panelName + '-detail').toggle(expanded);
  }

  function buildHeaderCard(data) {
    let html = '<div class="card mb-3"><div class="card-body py-2 px-3">';
    html += '<div class="d-flex justify-content-between align-items-start">';
    html += buildHeaderLeft(data);
    html += buildHeaderRight(data);
    html += '</div>';
    html += buildParentLink(data);
    html += buildWorkflowInput(data);
    html += buildWorkflowState(data);
    html += '</div></div>';
    return html;
  }

  function buildHeaderLeft(data) {
    return '<div>'
      + '<h5 class="mb-1" style="color:var(--text-primary);">'
      + '<i class="fas fa-cogs" style="color:var(--color-accent);"></i> '
      + escHtml(data.workflow_id)
      + ' <span class="btn-copy" onclick="copyText(' + jsStringAttr(data.workflow_id) + ')" title="Copy">'
      + '<i class="fas fa-copy icon-sm text-muted-custom"></i></span></h5>'
      + '<span class="meta-label">Class</span> <span class="meta-value">' + escHtml(data.workflow_class) + '</span>'
      + ' &middot; <span class="meta-label">Method</span> <span class="meta-value">' + escHtml(data.workflow_method) + '</span>'
      + '</div>';
  }

  function buildHeaderRight(data) {
    let html = '<div class="text-right">'
      + '<span class="badge badge-' + statusBadgeClass(data.status) + ' badge-state">' + escHtml(data.status) + '</span>';
    if (data.current_state) {
      html += ' <span class="badge badge-info badge-state">' + escHtml(data.current_state) + '</span>';
    }
    html += '<br><small class="text-muted-custom">';
    if (data.created_at) {
      html += 'Created ' + formatDate(data.created_at);
      const endMs = (data.updated_at && isTerminalStatus(data.status))
        ? new Date(data.updated_at).getTime()
        : new Date().getTime();
      const startMs = new Date(data.created_at).getTime();
      if (endMs > startMs) {
        html += ' &middot; Duration: <b>' + formatDuration(endMs - startMs) + '</b>';
      }
    }
    html += '</small></div>';
    return html;
  }

  function buildParentLink(data) {
    if (!data.parent_workflow_id) return '';
    return '<div class="mt-1"><span class="meta-label">Parent</span> '
      + '<a href="#' + ROUTE_PREFIX + encodeURIComponent(data.parent_workflow_id)
      + '" style="color:var(--color-accent);">' + escHtml(data.parent_workflow_id) + '</a></div>';
  }

  function buildWorkflowInput(data) {
    if (data.workflow_input == null) return '';
    const raw = JSON.stringify(data.workflow_input);
    const preview = raw.substring(0, INPUT_PREVIEW_MAX_CHARS);
    const expanded = isWorkflowJsonPanelExpanded(data.workflow_id, 'input');
    return '<div class="mt-1"><span class="meta-label">Input</span> '
      + '<span class="json-toggle" onclick="toggleWorkflowJsonPanel(' + jsStringAttr(data.workflow_id) + ', \'input\');">'
      + escHtml(preview)
      + ' <i class="fas fa-chevron-down icon-sm"></i></span>'
      + '<div class="json-detail" id="wf-input-detail" style="display:' + (expanded ? 'block' : 'none') + ';">'
      + escHtml(JSON.stringify(data.workflow_input, null, 2)) + '</div></div>';
  }

  function buildWorkflowState(data) {
    if (!data.state_fields || Object.keys(data.state_fields).length === 0) return '';
    const raw = JSON.stringify(data.state_fields);
    const preview = raw.substring(0, INPUT_PREVIEW_MAX_CHARS);
    const expanded = isWorkflowJsonPanelExpanded(data.workflow_id, 'state');
    return '<div class="mt-1"><span class="meta-label">State</span> '
      + '<span class="json-toggle" onclick="toggleWorkflowJsonPanel(' + jsStringAttr(data.workflow_id) + ', \'state\');">'
      + escHtml(preview)
      + ' <i class="fas fa-chevron-down icon-sm"></i></span>'
      + '<div class="json-detail" id="wf-state-detail" style="display:' + (expanded ? 'block' : 'none') + ';">'
      + escHtml(JSON.stringify(data.state_fields, null, 2)) + '</div></div>';
  }

  // ═══════════════════════════════════════════════
  //  Timeline
  // ═══════════════════════════════════════════════

  /** Renders the full vis-timeline with legend, groups, and click-to-detail. */
  function renderTimeline(data) {
    if (timeline) {
      timeline.destroy();
      timeline = null;
    }
    timelineItemMeta = {};

    const built = buildTimelineData(data);
    allTimelineItems = built.items;
    timelineGroups = built.groups;

    const container = $('#tab-timeline')[0];
    container.innerHTML = buildLegendHtml()
      + '<div id="vis-container" style="min-height:180px;"></div>'
      + '<div id="timeline-detail"></div>';

    const bounds = computeTimeBounds(built.items, data.status, getActiveFilters());
    const opts = buildTimelineOptions(bounds, data.status);

    filteredView = new vis.DataView(built.items, {
      filter: function(item) {
        if (!showChildren && item._isChild) return false;
        if (!item._filterType) return true;
        return getActiveFilters().indexOf(item._filterType) >= 0;
      }
    });
    var initialActive = getActiveFilters();
    const initialGroups = new vis.DataSet(built.groups.get({
      filter: function(g) {
        if (!showChildren && g._isChild) return false;
        if (g._filterType && initialActive.indexOf(g._filterType) < 0) return false;
        return true;
      }
    }));
    timeline = new vis.Timeline($('#vis-container')[0], filteredView, initialGroups, opts);
    setTimelineToRealBounds(false);
    timeline.on('rangechange', extendTerminalStateItemsToWindow);
    timeline.on('rangechanged', extendTerminalStateItemsToWindow);
    extendTerminalStateItemsToWindow();

    timeline.on('select', function(props) {
      if (!props.items || props.items.length === 0) {
        $('#timeline-detail').html('');
        return;
      }
      const meta = timelineItemMeta[props.items[0]];
      if (!meta) {
        $('#timeline-detail').html('');
        return;
      }
      $('#timeline-detail').html(renderDetailPanel(meta));
    });
  }

  /** Updates timeline data in-place — the timeline stays alive, view/zoom never resets. */
  function refreshTimelineData(data) {
    if (!allTimelineItems || !timeline) return;
    timelineItemMeta = {};

    const built = buildTimelineData(data);

    allTimelineItems.clear();
    allTimelineItems.add(built.items.get());
    timelineGroups.clear();
    timelineGroups.add(built.groups.get());

    const bounds = computeTimeBounds(built.items, data.status, getActiveFilters());
    timeline.setOptions(timelineWindowOptions(bounds, data.status));
    extendTerminalStateItemsToWindow();
  }

  /**
   * Builds vis.js DataSets for the parent workflow and all children.
   * Returns { items: vis.DataSet, groups: vis.DataSet }.
   */
  function buildTimelineData(data) {
    const groups = new vis.DataSet();
    const items = new vis.DataSet();
    const idCounter = { v: 0 };

    addWorkflowItems(data, groups, items, idCounter, null);
    (data.child_workflows || []).forEach(function(child) {
      addWorkflowItems(child, groups, items, idCounter, child.workflow_id);
    });

    return { items: items, groups: groups };
  }

  /**
   * Computes min/max time bounds from timeline items.
   * Uses proportional padding (5% of range, clamped to 30s–1h) for tight framing.
   * @param {vis.DataSet} items - The full item DataSet.
   * @param {string} status - The workflow status.
   * @param {string[]|null} activeFilters - If provided, only include items matching these types.
   */
  function computeTimeBounds(items, status, activeFilters) {
    const timestamps = [];
    items.forEach(function(item) {
      if (!showChildren && item._isChild) return;
      if (activeFilters && item._filterType && activeFilters.indexOf(item._filterType) < 0) return;
      timestamps.push(item.start.getTime());
      if (item.end && !item._terminalOpenEnded) timestamps.push(item.end.getTime());
    });

    if (!timestamps.length) {
      return { min: null, max: new Date().getTime() + MIN_BUFFER_MS };
    }

    const rawMin = Math.min.apply(null, timestamps);
    const rawMax = Math.max.apply(null, timestamps);
    const range = rawMax - rawMin;
    const buffer = Math.max(MIN_BUFFER_MS, Math.min(MAX_BUFFER_MS, range * BUFFER_RATIO));

    const minTime = rawMin - buffer;
    let maxTime;
    if (isTerminalStatus(status)) {
      maxTime = rawMax + buffer;
    } else {
      maxTime = Math.max(new Date().getTime(), rawMax) + buffer;
    }

    return { min: minTime, max: maxTime };
  }

  /** Builds the vis-timeline options object. */
  function buildTimelineOptions(bounds, status) {
    const opts = {
      stack: false,
      showCurrentTime: !isTerminalStatus(status),
      orientation: 'top',
      margin: { item: 0 },
      zoomMin: ZOOM_MIN_MS,
      tooltip: { followMouse: true, overflowMethod: 'cap' },
      groupOrder: 'order',
      moment: function(date) { return vis.moment(date).utc(); }
    };
    Object.assign(opts, timelineWindowOptions(bounds, status));
    return opts;
  }

  /** Builds min/max/zoom options. Terminal workflows intentionally have no max pan bound. */
  function timelineWindowOptions(bounds, status) {
    const opts = {};
    if (bounds.min) opts.min = new Date(bounds.min);
    if (bounds.max) {
      if (isTerminalStatus(status)) {
        opts.max = null;
      } else {
        opts.max = new Date(bounds.max);
      }
      if (bounds.min) opts.zoomMax = (bounds.max - bounds.min) * ZOOM_MAX_RATIO;
    }
    return opts;
  }

  /** Fits to real activity timestamps, excluding open-ended terminal-state visual extensions. */
  function setTimelineToRealBounds(animated) {
    if (!timeline || !allTimelineItems || !currentData) return;
    const bounds = computeTimeBounds(allTimelineItems, currentData.status, getActiveFilters());
    if (bounds.min != null && bounds.max != null) {
      timeline.setWindow(new Date(bounds.min), new Date(bounds.max), { animation: !!animated });
    } else {
      timeline.fit({ animation: !!animated });
    }
    extendTerminalStateItemsToWindow();
  }

  /** Returns the latest real activity timestamp, ignoring terminal visual extension ends. */
  function latestRealTimestamp(data) {
    return collectLatestRealTimestamp(data) || new Date().getTime();
  }

  function collectLatestRealTimestamp(data) {
    let latest = 0;
    function addTs(pt) {
      if (!pt) return;
      const ms = toDate(pt).getTime();
      if (ms > latest) latest = ms;
    }
    (data.state_history || []).forEach(function(s) { addTs(s.timestamp); });
    (data.event_history || []).forEach(function(e) { addTs(e.timestamp); });
    (data.after_hook_history || []).forEach(function(h) {
      addTs(h.timestamp);
      if (h.handler_span) addTs(h.handler_span.end);
    });
    (data.timeout_history || []).forEach(function(t) {
      addTs(t.timestamp);
      if (t.handler_span) addTs(t.handler_span.end);
    });
    (data.transition_log || []).forEach(function(t) {
      if (t.span) addTs(t.span.end);
      if (t.handler_span) addTs(t.handler_span.end);
      if (t.initial_middleware_span) addTs(t.initial_middleware_span.end);
      if (t.before_middleware_span) addTs(t.before_middleware_span.end);
      if (t.on_exit_span) addTs(t.on_exit_span.end);
      if (t.on_entry_span) addTs(t.on_entry_span.end);
      if (t.after_middleware_span) addTs(t.after_middleware_span.end);
      if (t.terminal_middleware_span) addTs(t.terminal_middleware_span.end);
    });
    (data.action_history || []).forEach(function(a) {
      addTs(a.start_time);
      addTs(a.end_time);
    });
    (data.child_workflows || []).forEach(function(child) {
      latest = Math.max(latest, collectLatestRealTimestamp(child));
    });
    return latest;
  }

  function terminalStateVisualEnd(data, stateStartMs) {
    const latest = Math.max(latestRealTimestamp(data), stateStartMs);
    const visibleEnd = timeline ? timeline.getWindow().end.getTime() + MIN_BUFFER_MS : 0;
    return Math.max(latest + MIN_BUFFER_MS, visibleEnd, stateStartMs + MIN_VISUAL_RANGE_MS);
  }

  /** Keeps terminal final-state bars visible when users pan beyond the last real timestamp. */
  function extendTerminalStateItemsToWindow() {
    if (!timeline || !allTimelineItems || !currentData || !isTerminalStatus(currentData.status)) return;
    const windowEnd = timeline.getWindow().end.getTime() + MIN_BUFFER_MS;
    allTimelineItems.forEach(function(item) {
      if (!item._terminalOpenEnded) return;
      const targetEnd = Math.max(windowEnd, item._actualEndMs || item.start.getTime() + MIN_VISUAL_RANGE_MS);
      if (!item.end || item.end.getTime() < targetEnd) {
        allTimelineItems.update(Object.assign({}, item, { end: new Date(targetEnd) }));
      }
    });
  }

  /** Builds the filter legend HTML with simplified group-level toggles. */
  function buildLegendHtml() {
    var html = '<div class="legend">';
    FILTER_ITEMS.forEach(function(item) {
      var dotStyle = 'background:' + item.color + ';' + (item.dashed ? 'border:1px dashed var(--text-dim);' : '');
      var checkedAttr = item.unchecked ? '' : ' checked';
      html += '<label><input type="checkbox"' + checkedAttr + ' onchange="filterTimeline()" data-filter-type="' + item.type + '"> '
        + '<span class="legend-dot" style="' + dotStyle + '"></span>' + item.label + '</label>';
    });
    html += '<label style="border-left:1px solid var(--border-subtle);padding-left:12px;">'
      + '<input type="checkbox" onchange="toggleChildren(this.checked)"> '
      + '<i class="fas fa-sitemap icon-sm"></i> Children'
      + '</label>';
    html += '<span class="ml-auto"></span>'
      + '<button class="btn btn-sm btn-outline-secondary" onclick="zoomIn()" title="Zoom in"><i class="fas fa-search-plus"></i></button> '
      + '<button class="btn btn-sm btn-outline-secondary" onclick="zoomOut()" title="Zoom out"><i class="fas fa-search-minus"></i></button> '
      + '<button class="btn btn-sm btn-outline-secondary" onclick="goToStart()" title="Go to start"><i class="fas fa-step-backward"></i> Start</button> '
      + '<button class="btn btn-sm btn-outline-secondary" onclick="goToLatest()" title="Go to latest activity"><i class="fas fa-fast-forward"></i> Latest</button> '
      + '<button class="btn btn-sm btn-outline-secondary" onclick="fitTimeline()" title="Fit to all visible items"><i class="fas fa-expand-arrows-alt"></i> Fit</button>';
    html += '</div>';
    return html;
  }

  /** Returns the list of currently checked filter types. */
  function getActiveFilters() {
    var active = [];
    $('.legend input[data-filter-type]').each(function() {
      if (this.checked) active.push($(this).attr('data-filter-type'));
    });
    return active;
  }

  /** Toggles visibility of child workflow groups and items. */
  function toggleChildren(visible) {
    showChildren = visible;
    if (filteredView) filteredView.refresh();
    if (timeline && timelineGroups) {
      var active = getActiveFilters();
      var visibleGroups = timelineGroups.get({
        filter: function(g) {
          if (!showChildren && g._isChild) return false;
          if (g._filterType && active.indexOf(g._filterType) < 0) return false;
          return true;
        }
      });
      timeline.setGroups(new vis.DataSet(visibleGroups));
    }
    if (timeline && allTimelineItems && currentData) {
      const bounds = computeTimeBounds(allTimelineItems, currentData.status, getActiveFilters());
      timeline.setOptions(timelineWindowOptions(bounds, currentData.status));
      setTimelineToRealBounds(true);
    }
  }

  /** Refreshes the timeline DataView filter, updates group visibility, and recomputes bounds. */
  function filterTimeline() {
    if (filteredView) filteredView.refresh();
    if (timeline && timelineGroups) {
      var active = getActiveFilters();
      var visibleGroups = timelineGroups.get({
        filter: function(g) {
          if (!showChildren && g._isChild) return false;
          if (g._filterType && active.indexOf(g._filterType) < 0) return false;
          return true;
        }
      });
      timeline.setGroups(new vis.DataSet(visibleGroups));
    }
    if (timeline && allTimelineItems && currentData) {
      var bounds = computeTimeBounds(allTimelineItems, currentData.status, getActiveFilters());
      timeline.setOptions(timelineWindowOptions(bounds, currentData.status));
      setTimelineToRealBounds(true);
    }
  }

  /** Fits the timeline zoom to show all visible items. */
  function fitTimeline() {
    setTimelineToRealBounds(true);
  }

  /** Zooms the timeline in by ZOOM_STEP. */
  function zoomIn() {
    if (timeline) timeline.zoomIn(ZOOM_STEP, { animation: true });
  }

  /** Zooms the timeline out by ZOOM_STEP. */
  function zoomOut() {
    if (timeline) timeline.zoomOut(ZOOM_STEP, { animation: true });
  }

  /** Moves the timeline view to the earliest visible item. */
  function goToStart() {
    if (!timeline || !allTimelineItems) return;
    const active = getActiveFilters();
    let earliest = Infinity;
    allTimelineItems.forEach(function(item) {
      if (!showChildren && item._isChild) return;
      if (item._filterType && active.indexOf(item._filterType) < 0) return;
      const t = item.start.getTime();
      if (t < earliest) earliest = t;
    });
    if (earliest < Infinity) timeline.moveTo(new Date(earliest), { animation: true });
  }

  /** Moves the timeline view to the latest activity: "now" for running workflows, last real timestamp for terminal ones. */
  function goToLatest() {
    if (!timeline || !currentData) return;
    if (isTerminalStatus(currentData.status)) {
      timeline.moveTo(new Date(latestRealTimestamp(currentData)), { animation: true });
      extendTerminalStateItemsToWindow();
    } else {
      timeline.moveTo(new Date(), { animation: true });
    }
  }

  // ── Timeline: Add Items ──────────────────────

  /** Adds all timeline items across 4 groups (rows) for a single workflow. */
  function addWorkflowItems(data, groups, items, idCounter, label) {
    var isChild = label != null;
    var prefix = label ? label + ' / ' : '';
    var order = idCounter.v;
    var displayPrefix = label
      ? '<a href="#' + ROUTE_PREFIX + encodeURIComponent(data.workflow_id) + '" style="color:var(--text-secondary);">' + escHtml(label) + '</a> / '
      : '';
    var gStates = prefix + 'States';
    var gTransitions = prefix + 'Transitions';
    var gDetails = prefix + 'Details';
    var gSideEffects = prefix + 'Side Effects';
    groups.add({ id: gStates, content: displayPrefix + '<b>States</b>', order: order, _isChild: !!isChild, _filterType: 'states' });
    groups.add({ id: gTransitions, content: displayPrefix + '<b>Transitions</b>', order: order + 1, _isChild: !!isChild, _filterType: 'transitions' });
    groups.add({ id: gDetails, content: displayPrefix + '<b>Details</b>', order: order + 2, _isChild: !!isChild, _filterType: 'details' });
    groups.add({ id: gSideEffects, content: displayPrefix + '<b>Side Effects</b>', order: order + 3, _isChild: !!isChild, _filterType: 'side-effects' });
    idCounter.v += 4;
    var transitionLookup = buildTransitionLookup(data.transition_log || []);
    addStateItems(data, gStates, items, idCounter, isChild);
    addTransitionItems(data.transition_log || [], gTransitions, items, idCounter, isChild);
    addEventItemsWithOutcome(data.event_history || [], transitionLookup, gTransitions, items, idCounter, isChild);
    addAfterHookItems(data.after_hook_history || [], gTransitions, items, idCounter, isChild);
    addTimeoutItems(data.timeout_history || [], gTransitions, items, idCounter, isChild);
    addPendingTimerItems(data.pending_timers || [], gTransitions, items, idCounter, isChild);
    addHandlerItems(data.transition_log || [], data.event_history || [], gDetails, items, idCounter, isChild);
    addAfterHookHandlerItems(data.after_hook_history || [], gDetails, items, idCounter, isChild);
    addHookItems(data.transition_log || [], gDetails, items, idCounter, isChild);
    addMiddlewareItems(data.transition_log || [], gDetails, items, idCounter, isChild);
    addSideEffectItems(data.action_history || [], gSideEffects, items, idCounter, isChild);
  }

  /** Builds an index from durable event index → transition for coloring events by outcome. */
  function buildTransitionLookup(transitionLog) {
    var lookup = {};
    (transitionLog || []).forEach(function(t) {
      if (t.trigger_kind === 'EVENT' && t.event_index != null) lookup[t.event_index] = t;
    });
    return lookup;
  }

  function addStateItems(data, gid, items, idCounter, isChild) {
    var sh = data.state_history || [];
    var terminal = isTerminalStatus(data.status);
    for (var i = 0; i < sh.length; i++) {
      var s = sh[i], next = sh[i + 1], itemId = idCounter.v++;
      var isFirst = (i === 0), isLast = (i === sh.length - 1);
      var stateCls = isFirst ? 'tl-state tl-state-initial' : 'tl-state';
      if (isLast && terminal) stateCls = 'tl-state tl-state-terminal';
      if (i % 2 === 1) stateCls += ' tl-alt';
      var sDate = toDate(s.timestamp);
      var sMs = sDate.getTime();
      var terminalOpenEnded = isLast && terminal;
      var stateEnd = next ? toDate(next.timestamp).getTime() : (terminalOpenEnded ? terminalStateVisualEnd(data, sMs) : new Date().getTime());
      if (stateEnd - sMs < MIN_VISUAL_RANGE_MS) stateEnd = sMs + MIN_VISUAL_RANGE_MS;
      items.add({ id: itemId, group: gid, content: s.state, start: sDate, end: new Date(stateEnd),
        type: 'range', className: stateCls, _filterType: 'states', _isChild: isChild,
        _terminalOpenEnded: terminalOpenEnded, _actualEndMs: latestRealTimestamp(data) });
      var endPt = next ? next.timestamp : null;
      timelineItemMeta[itemId] = { type: 'state', state: s.state, startPt: s.timestamp, endPt: endPt,
        duration: next ? formatNanoDuration(durationNanosBetween(s.timestamp, next.timestamp)) : (terminalOpenEnded ? '∞' : '(current)'), terminal: terminalOpenEnded };
    }
  }

  /** Adds transition ranges from transition log, including ignored and invalid attempts. */
  function addTransitionItems(transitionLog, gid, items, idCounter, isChild) {
    var altIdx = 0;
    (transitionLog || []).forEach(function(t) {
      var itemId = idCounter.v++;
      var cls = 'tl-transition-to';
      if (t.outcome === 'STAY') cls = 'tl-transition-stay';
      else if (t.outcome === 'IGNORE_GUARD') cls = 'tl-transition-guard';
      else if (t.outcome.indexOf('IGNORE') >= 0) cls = 'tl-transition-ignore';
      else if (t.outcome.indexOf('INVALID') >= 0) cls = 'tl-transition-invalid';
      if (altIdx++ % 2 === 1) cls += ' tl-alt';
      var lbl = t.from_state ? (t.from_state + ' \u2192 ' + (t.to_state || t.outcome.toLowerCase())) : ('\u2192 ' + (t.to_state || t.outcome.toLowerCase()));
      var startDate = toDate(t.span.start), endDate = toDate(t.span.end);
      if (endDate.getTime() - startDate.getTime() < MIN_VISUAL_RANGE_MS) endDate = new Date(startDate.getTime() + MIN_VISUAL_RANGE_MS);
      items.add({ id: itemId, group: gid, content: lbl, start: startDate, end: endDate,
        type: 'range', className: cls, _filterType: 'transitions', _isChild: isChild });
      timelineItemMeta[itemId] = { type: 'transition', fromState: t.from_state, toState: t.to_state,
        outcome: t.outcome, triggerKind: t.trigger_kind, triggerName: t.trigger_name,
        span: t.span, handlerSpan: t.handler_span, initialMiddlewareSpan: t.initial_middleware_span,
        beforeMiddlewareSpan: t.before_middleware_span,
        onExitSpan: t.on_exit_span, onEntrySpan: t.on_entry_span,
        afterMiddlewareSpan: t.after_middleware_span, terminalMiddlewareSpan: t.terminal_middleware_span };
    });
  }

  /** Adds event points colored by outcome (handled/unhandled/guarded). */
  function addEventItemsWithOutcome(events, transitionLookup, gid, items, idCounter, isChild) {
    events.forEach(function(ev, eventIndex) {
      var itemId = idCounter.v++, cls = 'tl-event-handled', matched = null;
      var evDate = toDate(ev.timestamp);
      matched = transitionLookup[eventIndex];
      if (matched) {
        if (matched.outcome === 'IGNORE_GUARD') cls = 'tl-event-guarded';
        else if (matched.outcome === 'INVALID_NO_HANDLER') cls = 'tl-event-unhandled';
      }
      items.add({ id: itemId, group: gid, content: ev.event_type, start: evDate,
        type: 'point', className: cls, _filterType: 'transitions', _isChild: isChild });
      timelineItemMeta[itemId] = { type: 'event', eventType: ev.event_type, timestampPt: ev.timestamp,
        payload: ev.payload, transitionOutcome: matched ? matched.outcome : null,
        transitionTo: matched ? matched.to_state : null };
    });
  }

  /** Adds event/timeout handler spans as ranges in Details row. */
  function addHandlerItems(transitionLog, eventHistory, gid, items, idCounter, isChild) {
    var altIdx = 0;
    (transitionLog || []).forEach(function(t) {
      if (!t.handler_span || t.handler_span.duration_nanos <= 0) return;
      var itemId = idCounter.v++;
      var label = t.trigger_name + ' handler';
      var cls = t.trigger_kind === 'TIMEOUT' ? 'tl-handler-timeout' : 'tl-handler-event';
      if (altIdx++ % 2 === 1) cls += ' tl-alt';
      var startDate = toDate(t.handler_span.start), endDate = toDate(t.handler_span.end);
      var r = ensureMinRange(startDate.getTime(), endDate.getTime());
      items.add({ id: itemId, group: gid, content: label, start: new Date(r.start), end: new Date(r.end),
        type: 'range', className: cls, _filterType: 'details', _isChild: isChild });
      var payload = null;
      if (t.trigger_kind === 'EVENT' && t.event_index != null && eventHistory[t.event_index]) {
        payload = eventHistory[t.event_index].payload;
      }
      timelineItemMeta[itemId] = { type: 'handler', triggerName: t.trigger_name, triggerKind: t.trigger_kind,
        durationNanos: t.handler_span.duration_nanos, startPt: t.handler_span.start, endPt: t.handler_span.end,
        payload: payload };
    });
  }

  /** Adds after-hook handler spans as ranges in Details row. */
  function addAfterHookHandlerItems(hooks, gid, items, idCounter, isChild) {
    var altIdx = 0;
    (hooks || []).forEach(function(h) {
      if (!h.handler_span || h.handler_span.duration_nanos <= 0) return;
      var itemId = idCounter.v++;
      var cls = 'tl-handler-after';
      if (altIdx++ % 2 === 1) cls += ' tl-alt';
      var startDate = toDate(h.handler_span.start), endDate = toDate(h.handler_span.end);
      var r = ensureMinRange(startDate.getTime(), endDate.getTime());
      items.add({ id: itemId, group: gid, content: 'after(' + h.duration + ') handler', start: new Date(r.start), end: new Date(r.end),
        type: 'range', className: cls, _filterType: 'details', _isChild: isChild });
      timelineItemMeta[itemId] = { type: 'handler', triggerName: 'after(' + h.duration + ')', triggerKind: 'AFTER',
        durationNanos: h.handler_span.duration_nanos, startPt: h.handler_span.start, endPt: h.handler_span.end,
        payload: null };
    });
  }

  /** Adds onExit/onEntry hook ranges to Details row. */
  function addHookItems(transitionLog, gid, items, idCounter, isChild) {
    (transitionLog || []).forEach(function(t) {
      if (t.on_exit_span && t.on_exit_span.duration_nanos > 0) {
        var id1 = idCounter.v++, exitStart = toDate(t.on_exit_span.start), exitEnd = toDate(t.on_exit_span.end);
        if (exitEnd.getTime() - exitStart.getTime() < MIN_VISUAL_RANGE_MS) exitEnd = new Date(exitStart.getTime() + MIN_VISUAL_RANGE_MS);
        items.add({ id: id1, group: gid, content: 'onExit', start: exitStart, end: exitEnd,
          type: 'range', className: 'tl-hook-exit', _filterType: 'details', _isChild: isChild });
        timelineItemMeta[id1] = { type: 'hook', hookType: 'onExit', fromState: t.from_state,
          durationNanos: t.on_exit_span.duration_nanos, startPt: t.on_exit_span.start, endPt: t.on_exit_span.end };
      }
      if (t.on_entry_span && t.on_entry_span.duration_nanos > 0) {
        var id2 = idCounter.v++, entryStart = toDate(t.on_entry_span.start), entryEnd = toDate(t.on_entry_span.end);
        if (entryEnd.getTime() - entryStart.getTime() < MIN_VISUAL_RANGE_MS) entryEnd = new Date(entryStart.getTime() + MIN_VISUAL_RANGE_MS);
        items.add({ id: id2, group: gid, content: 'onEntry', start: entryStart, end: entryEnd,
          type: 'range', className: 'tl-hook-entry', _filterType: 'details', _isChild: isChild });
        timelineItemMeta[id2] = { type: 'hook', hookType: 'onEntry', toState: t.to_state,
          durationNanos: t.on_entry_span.duration_nanos, startPt: t.on_entry_span.start, endPt: t.on_entry_span.end };
      }
    });
  }

  /** Adds transition-scoped middleware spans as ranges in Details row. */
  function addMiddlewareItems(transitionLog, gid, items, idCounter, isChild) {
    var altIdx = 0;
    (transitionLog || []).forEach(function(t) {
      altIdx = addMiddlewareSpanItem(t, t.initial_middleware_span, 'Initial MW', 'Initial-state middleware', 'tl-mw-before', gid, items, idCounter, isChild, altIdx);
      altIdx = addMiddlewareSpanItem(t, t.before_middleware_span, 'Before MW', 'Before-transition middleware', 'tl-mw-before', gid, items, idCounter, isChild, altIdx);
      altIdx = addMiddlewareSpanItem(t, t.after_middleware_span, 'After MW', 'After-transition middleware', 'tl-mw-after', gid, items, idCounter, isChild, altIdx);
      altIdx = addMiddlewareSpanItem(t, t.terminal_middleware_span, 'Terminal MW', 'Terminal-state middleware', 'tl-mw-terminal', gid, items, idCounter, isChild, altIdx);
    });
  }

  function addMiddlewareSpanItem(t, span, content, label, cls, gid, items, idCounter, isChild, altIdx) {
    if (!span || span.duration_nanos < 0) return altIdx;
    var itemId = idCounter.v++;
    if (altIdx % 2 === 1) cls += ' tl-alt';
    var startDate = toDate(span.start);
    var startMs = startDate.getTime();
    var endMs = toDate(span.end).getTime();
    if (endMs - startMs < MIN_VISUAL_RANGE_MS) endMs = startMs + MIN_VISUAL_RANGE_MS;
    var transitionLabel = (t.from_state ? t.from_state : '\u2205') + ' \u2192 ' + (t.to_state || '\u2014');
    items.add({ id: itemId, group: gid, content: content + ' &middot; ' + transitionLabel, start: startDate, end: new Date(endMs),
      type: 'range', className: cls, _filterType: 'details', _isChild: isChild });
    timelineItemMeta[itemId] = { type: 'middleware', label: label, fromState: t.from_state, toState: t.to_state,
      outcome: t.outcome, triggerName: t.trigger_name, durationNanos: span.duration_nanos,
      startTimePt: span.start, endTimePt: span.end };
    return altIdx + 1;
  }

  /** Adds checkpointed actions as ranges in Side Effects row. */
  function addSideEffectItems(actions, gid, items, idCounter, isChild) {
    var altIdx = 0;
    (actions || []).forEach(function(a) {
      var itemId = idCounter.v++;
      var cls = 'tl-action' + (!a.successful ? ' tl-action-fail' : '') + (a.compensation ? ' tl-action-comp' : '');
      if (altIdx++ % 2 === 1) cls += ' tl-alt';
      var lbl = (a.compensation ? '&#x27F2; ' : '') + a.action_class + '.' + a.action_method + '()';
      var startDate = toDate(a.start_time);
      var startMs = startDate.getTime();
      var endMs = a.end_time ? toDate(a.end_time).getTime() : (startMs + MIN_VISUAL_RANGE_MS);
      if (endMs - startMs < MIN_VISUAL_RANGE_MS) endMs = startMs + MIN_VISUAL_RANGE_MS;
      items.add({ id: itemId, group: gid, content: lbl, start: startDate, end: new Date(endMs),
        type: 'range', className: cls, _filterType: 'side-effects', _isChild: isChild });
      timelineItemMeta[itemId] = { type: 'action', actionClass: a.action_class, actionMethod: a.action_method,
        iteration: a.iteration, successful: a.successful, compensation: a.compensation,
        startTime: startDate, endTime: a.end_time ? toDate(a.end_time) : null,
        startTimePt: a.start_time, endTimePt: a.end_time, input: a.input, result: a.result,
        transitionLabel: a.transition_label, transitionPhase: a.transition_phase,
        transitionScopeKind: a.transition_scope_kind };
    });
  }

  function addAfterHookItems(hooks, gid, items, idCounter, isChild) {
    hooks.forEach(function(h) {
      var itemId = idCounter.v++;
      var hDate = toDate(h.timestamp);
      items.add({ id: itemId, group: gid, content: 'after(' + h.duration + ')', start: hDate,
        type: 'point', className: 'tl-after', _filterType: 'transitions', _isChild: isChild });
      timelineItemMeta[itemId] = { type: 'after', duration: h.duration, timestampPt: h.timestamp, handlerSpan: h.handler_span };
    });
  }

  function addTimeoutItems(timeouts, gid, items, idCounter, isChild) {
    timeouts.forEach(function(t) {
      var itemId = idCounter.v++;
      var tDate = toDate(t.timestamp);
      items.add({ id: itemId, group: gid, content: 'timeout(' + t.duration + ')', start: tDate,
        type: 'point', className: 'tl-timeout', _filterType: 'transitions', _isChild: isChild });
      timelineItemMeta[itemId] = { type: 'timeout', duration: t.duration, timestampPt: t.timestamp };
    });
  }

  function addPendingTimerItems(pendingTimers, gid, items, idCounter, isChild) {
    var now = new Date().getTime();
    pendingTimers.forEach(function(pt) {
      var dlDate = toDate(pt.deadline);
      if (dlDate.getTime() <= now) return;
      var itemId = idCounter.v++;
      items.add({ id: itemId, group: gid, content: pt.type + '(' + pt.duration + ')', start: dlDate,
        type: 'point', className: 'tl-pending', _filterType: 'pending', _isChild: isChild });
      timelineItemMeta[itemId] = { type: 'pending-' + pt.type, duration: pt.duration, deadlinePt: pt.deadline };
    });
  }

  // ── Timeline: Detail Panel ───────────────────

  /** Renders the detail panel HTML for a clicked timeline item. */
  function renderDetailPanel(meta) {
    var renderers = {
      state: renderStateDetail,
      event: renderEventDetail,
      action: renderActionDetail,
      after: renderAfterDetail,
      timeout: renderTimeoutDetail,
      transition: renderTransitionDetail,
      handler: renderHandlerDetail,
      hook: renderHookDetail,
      middleware: renderMiddlewareDetail
    };
    var renderer = renderers[meta.type] || renderPendingDetail;
    return '<div class="detail-panel">' + renderer(meta) + '</div>';
  }

  function renderStateDetail(meta) {
    var badgeCls = meta.terminal ? 'badge-secondary' : 'badge-success';
    var html = '<span class="detail-label">State</span> '
      + '<span class="badge ' + badgeCls + '">' + escHtml(meta.state) + '</span>'
      + (meta.terminal ? ' <span class="badge badge-dark">TERMINAL</span>' : '');
    html += '<br><span class="detail-label">Started</span> ' + formatPreciseDate(meta.startPt);
    if (meta.endPt) html += ' &middot; <span class="detail-label">Ended</span> ' + formatPreciseDate(meta.endPt);
    html += ' &middot; <span class="detail-label">Duration</span> ' + meta.duration;
    return html;
  }

  function renderEventDetail(meta) {
    var html = '<span class="detail-label">Event</span> '
      + '<span class="badge badge-info">' + escHtml(meta.eventType) + '</span>'
      + ' &middot; <span class="detail-label">At</span> ' + formatPreciseDate(meta.timestampPt);
    if (meta.transitionOutcome) {
      var badge = '<span class="badge badge-' + (OUTCOME_BADGE_MAP[meta.transitionOutcome] || 'light') + '">'
        + (OUTCOME_LABEL_MAP[meta.transitionOutcome] || meta.transitionOutcome) + '</span>';
      html += ' &middot; <span class="detail-label">Result</span> ' + badge;
      if (meta.transitionTo) html += ' \u2192 ' + escHtml(meta.transitionTo);
    }
    if (meta.payload != null) {
      html += '<div class="detail-label mt-2">Payload</div><pre>'
        + escHtml(JSON.stringify(meta.payload, null, 2)) + '</pre>';
    }
    return html;
  }

  function renderTransitionDetail(meta) {
    var badge = '<span class="badge badge-' + (OUTCOME_BADGE_MAP[meta.outcome] || 'light') + '">'
      + (OUTCOME_LABEL_MAP[meta.outcome] || meta.outcome) + '</span>';
    var html = '<span class="detail-label">Transition</span> '
      + (meta.fromState ? escHtml(meta.fromState) : '\u2205') + ' \u2192 ' + escHtml(meta.toState || '\u2014')
      + ' &middot; ' + badge
      + ' &middot; <span class="detail-label">Trigger</span> ' + escHtml(meta.triggerName);
    html += '<br><span class="detail-label">Started</span> ' + formatPreciseDate(meta.span.start)
      + ' &middot; <span class="detail-label">Ended</span> ' + formatPreciseDate(meta.span.end)
      + ' &middot; <span class="detail-label">Duration</span> ' + formatNanoDuration(meta.span.duration_nanos);
    var steps = [];
    if (meta.handlerSpan) steps.push('Handler: ' + formatNanoDuration(meta.handlerSpan.duration_nanos));
    if (meta.initialMiddlewareSpan) steps.push('Initial MW: ' + formatNanoDuration(meta.initialMiddlewareSpan.duration_nanos));
    if (meta.beforeMiddlewareSpan) steps.push('Before MW: ' + formatNanoDuration(meta.beforeMiddlewareSpan.duration_nanos));
    if (meta.onExitSpan) steps.push('onExit: ' + formatNanoDuration(meta.onExitSpan.duration_nanos));
    if (meta.onEntrySpan) steps.push('onEntry: ' + formatNanoDuration(meta.onEntrySpan.duration_nanos));
    if (meta.afterMiddlewareSpan) steps.push('After MW: ' + formatNanoDuration(meta.afterMiddlewareSpan.duration_nanos));
    if (meta.terminalMiddlewareSpan) steps.push('Terminal MW: ' + formatNanoDuration(meta.terminalMiddlewareSpan.duration_nanos));
    if (steps.length) html += '<div class="mt-1" style="font-size:0.85em;color:var(--text-muted);">' + steps.join(' &middot; ') + '</div>';
    return html;
  }

  function renderHandlerDetail(meta) {
    var html = '<span class="detail-label">Handler</span> ' + escHtml(meta.triggerName);
    html += '<br><span class="detail-label">Started</span> ' + formatPreciseDate(meta.startPt)
      + ' &middot; <span class="detail-label">Ended</span> ' + formatPreciseDate(meta.endPt)
      + ' &middot; <span class="detail-label">Duration</span> ' + formatNanoDuration(meta.durationNanos);
    if (meta.triggerKind === 'EVENT' && meta.payload != null) {
      html += '<div class="detail-label mt-2">Event Payload</div><pre>'
        + escHtml(JSON.stringify(meta.payload, null, 2)) + '</pre>';
    } else if (meta.triggerKind === 'TIMEOUT') {
      html += '<div class="detail-label mt-2">Timeout</div><span>' + escHtml(meta.triggerName) + '</span>';
    } else if (meta.triggerKind === 'AFTER') {
      html += '<div class="detail-label mt-2">After Hook</div><span>' + escHtml(meta.triggerName) + '</span>';
    }
    return html;
  }

  function renderHookDetail(meta) {
    var dur = meta.durationNanos ? formatNanoDuration(meta.durationNanos) : '\u2014';
    var html = '<span class="detail-label">' + escHtml(meta.hookType) + ' Hook</span>'
      + ' &middot; <span class="detail-label">State</span> ' + escHtml(meta.fromState || meta.toState || '\u2014');
    html += '<br><span class="detail-label">Started</span> ' + formatPreciseDate(meta.startPt)
      + ' &middot; <span class="detail-label">Ended</span> ' + formatPreciseDate(meta.endPt)
      + ' &middot; <span class="detail-label">Duration</span> ' + dur;
    return html;
  }

  function renderMiddlewareDetail(meta) {
    var dur = meta.durationNanos != null ? formatNanoDuration(meta.durationNanos) : '\u2014';
    var transitionLabel = (meta.fromState ? escHtml(meta.fromState) : '\u2205') + ' \u2192 ' + escHtml(meta.toState || '\u2014');
    var html = '<span class="detail-label">Middleware</span> ' + escHtml(meta.label || 'Middleware')
      + ' &middot; <span class="detail-label">Transition</span> ' + transitionLabel
      + ' &middot; <span class="detail-label">Trigger</span> ' + escHtml(meta.triggerName || '\u2014');
    html += '<br><span class="detail-label">Started</span> ' + formatPreciseDate(meta.startTimePt)
      + ' &middot; <span class="detail-label">Ended</span> ' + formatPreciseDate(meta.endTimePt)
      + ' &middot; <span class="detail-label">Duration</span> ' + dur;
    return html;
  }

  function renderActionDetail(meta) {
    let badge = meta.successful
      ? '<span class="badge badge-success">OK</span>'
      : '<span class="badge badge-danger">FAILED</span>';
    if (meta.compensation) {
      badge += ' <span class="badge badge-warning">COMPENSATE</span>';
    }

    var dur = (meta.endTimePt && meta.startTimePt) ? formatNanoDuration(durationNanosBetween(meta.startTimePt, meta.endTimePt)) : null;
    var html = '<span class="detail-label">Action</span> '
      + escHtml(meta.actionClass) + '.' + escHtml(meta.actionMethod) + '() #' + meta.iteration
      + ' &middot; ' + badge;
    html += '<br><span class="detail-label">Started</span> ' + formatPreciseDate(meta.startTimePt);
    if (meta.endTimePt) html += ' &middot; <span class="detail-label">Ended</span> ' + formatPreciseDate(meta.endTimePt);
    if (dur) html += ' &middot; <span class="detail-label">Duration</span> ' + dur;
    if (meta.transitionLabel) {
      html += '<br><span class="detail-label">Transition</span> ' + escHtml(meta.transitionLabel);
      if (meta.transitionPhase) html += ' &middot; <span class="detail-label">Phase</span> ' + escHtml(meta.transitionPhase);
      if (meta.transitionScopeKind === 'INFERRED') {
        html += ' &middot; <span class="badge badge-secondary">Scope inferred</span>';
      }
    }

    if (meta.input != null) {
      html += '<div class="detail-label mt-2">Input</div><pre>'
        + escHtml(JSON.stringify(meta.input, null, 2)) + '</pre>';
    } else {
      html += '<div class="detail-label mt-2">Input</div>'
        + '<span class="text-dim" style="font-size:0.85em;">Not captured (only stored for actions with @Compensate)</span>';
    }

    if (meta.result != null) {
      html += '<div class="detail-label mt-2">Result</div><pre>'
        + escHtml(JSON.stringify(meta.result, null, 2)) + '</pre>';
    }
    return html;
  }

  function renderAfterDetail(meta) {
    var html = '<span class="detail-label">After Hook</span> after(' + escHtml(meta.duration) + ')'
      + ' &middot; <span class="detail-label">At</span> ' + formatPreciseDate(meta.timestampPt);
    if (meta.handlerSpan) {
      html += ' &middot; <span class="detail-label">Handler Duration</span> ' + formatNanoDuration(meta.handlerSpan.duration_nanos);
    }
    return html;
  }

  function renderTimeoutDetail(meta) {
    return '<span class="detail-label">Timeout</span> timeout(' + escHtml(meta.duration) + ')'
      + ' &middot; <span class="detail-label">At</span> ' + formatPreciseDate(meta.timestampPt);
  }

  function renderPendingDetail(meta) {
    var pType = meta.type.replace('pending-', '');
    return '<span class="badge badge-dark">PENDING</span> '
      + '<span class="detail-label">' + escHtml(pType) + '</span> '
      + escHtml(pType) + '(' + escHtml(meta.duration) + ')'
      + ' &middot; <span class="detail-label">Scheduled for</span> ' + formatPreciseDate(meta.deadlinePt);
  }

  // ═══════════════════════════════════════════════
  //  Sequence Diagram
  // ═══════════════════════════════════════════════

  /** Renders the Mermaid sequence diagram tab. */
  function renderSequenceDiagram(data) {
    const code = data.sequence_diagram_mermaid;
    if (!code) {
      $('#tab-sequence').html('<p class="text-muted-custom" style="padding:20px;">No data.</p>');
      return;
    }

    // The onclick uses document.getElementById because it executes in global scope from an attribute
    const html = '<div class="d-flex justify-content-end mb-2">'
      + '<button class="btn btn-sm btn-outline-secondary btn-copy" '
      + 'onclick="copyText(document.getElementById(\'mermaid-source\').textContent)">'
      + '<i class="fas fa-copy"></i> Copy Mermaid</button></div>'
      + '<div class="mermaid-container">'
      + '<pre id="mermaid-source" style="display:none;">' + escHtml(code) + '</pre>'
      + '<div id="mermaid-render"></div></div>';
    $('#tab-sequence').html(html);

    try {
      mermaid.render('mermaid-svg', code)
        .then(function(result) {
          $('#mermaid-render').html(result.svg);
        })
        .catch(function(err) {
          console.warn('[SM Admin] Mermaid render failed:', err);
          $('#mermaid-render').html('<pre style="color:var(--text-primary);">' + escHtml(code) + '</pre>');
        });
    } catch (err) {
      console.warn('[SM Admin] Mermaid render error:', err);
      $('#mermaid-render').html('<pre style="color:var(--text-primary);">' + escHtml(code) + '</pre>');
    }
  }

  // ═══════════════════════════════════════════════
  //  History Tables
  // ═══════════════════════════════════════════════

  /** Renders all history tables (states, events, actions, after hooks, timeouts). */
  function renderTables(data) {
    jsonToggleId = 0;
    const isTerminalWf = isTerminalStatus(data.status);
    let html = '';

    html += tableCard('fa-layer-group', 'var(--color-state)', 'State History', 'success', data.state_history,
      '<th>#</th><th>State</th><th>Start</th><th>End</th><th>Duration</th>',
      function(s, i, arr) {
        const next = arr[i + 1];
        const isLast = (i === arr.length - 1);
        const badgeCls = (isLast && isTerminalWf) ? 'badge-secondary' : 'badge-success';
        const termLabel = (isLast && isTerminalWf)
          ? ' <span class="badge badge-dark" style="font-size:0.75em;">TERMINAL</span>' : '';
        return '<td>' + (i + 1) + '</td>'
          + '<td><span class="badge ' + badgeCls + '">' + escHtml(s.state) + '</span>' + termLabel + '</td>'
          + '<td>' + formatDate(toDate(s.timestamp)) + '</td>'
          + '<td>' + (next ? formatDate(toDate(next.timestamp)) : '(current)') + '</td>'
          + '<td>' + (next ? formatNanoDuration(durationNanosBetween(s.timestamp, next.timestamp)) : '&mdash;') + '</td>';
      });

    html += tableCard('fa-bolt', 'var(--color-accent)', 'Event History', 'info', data.event_history,
      '<th>#</th><th>Event</th><th>Timestamp</th><th>Payload</th>',
      function(e, i) {
        return '<td>' + (i + 1) + '</td>'
          + '<td><span class="badge badge-info">' + escHtml(e.event_type) + '</span></td>'
          + '<td>' + formatDate(toDate(e.timestamp)) + '</td>'
          + '<td>' + jsonCell(e.payload) + '</td>';
      });

    html += tableCard('fa-play-circle', 'var(--color-action)', 'Action History', 'purple', data.action_history,
      '<th>#</th><th>Action</th><th>Started</th><th>Duration</th><th>Status</th><th>Input</th><th>Result</th>',
      function(a, i) {
        const dur = a.end_time ? formatNanoDuration(durationNanosBetween(a.start_time, a.end_time)) : '...';
        let badge = a.successful
          ? '<span class="badge badge-success">OK</span>'
          : '<span class="badge badge-danger">FAILED</span>';
        if (a.compensation) badge += ' <span class="badge badge-warning">COMPENSATE</span>';
        return '<td>' + (i + 1) + '</td>'
          + '<td>' + escHtml(a.action_class) + '.' + escHtml(a.action_method) + '() #' + a.iteration + '</td>'
          + '<td>' + formatDate(toDate(a.start_time)) + '</td>'
          + '<td>' + dur + '</td>'
          + '<td>' + badge + '</td>'
          + '<td>' + jsonCell(a.input) + '</td>'
          + '<td>' + jsonCell(a.result) + '</td>';
      });

    html += tableCard('fa-clock', 'var(--color-after)', 'After Hook History', 'warning', data.after_hook_history,
      '<th>#</th><th>Duration</th><th>Fired At</th>',
      function(a, i) {
        return '<td>' + (i + 1) + '</td>'
          + '<td><span class="badge badge-warning">' + escHtml(a.duration) + '</span></td>'
          + '<td>' + formatDate(toDate(a.timestamp)) + '</td>';
      });

    html += tableCard('fa-hourglass-end', 'var(--color-danger)', 'Timeout History', 'danger', data.timeout_history,
      '<th>#</th><th>Duration</th><th>Fired At</th>',
      function(t, i) {
        return '<td>' + (i + 1) + '</td>'
          + '<td><span class="badge badge-danger">' + escHtml(t.duration) + '</span></td>'
          + '<td>' + formatDate(toDate(t.timestamp)) + '</td>';
      });

    html += tableCard('fa-exchange-alt', '#3498db', 'Transition Log', 'info', data.transition_log,
      '<th>#</th><th>From \u2192 To</th><th>Outcome</th><th>Trigger</th><th>Duration</th><th>Steps</th>',
      function(t, i) {
        var badge = '<span class="badge badge-' + (OUTCOME_BADGE_MAP[t.outcome] || 'light') + '">'
          + (OUTCOME_LABEL_MAP[t.outcome] || t.outcome) + '</span>';
        var to = t.to_state ? escHtml(t.to_state) : '\u2014';
        var dur = formatNanoDuration(t.span.duration_nanos);
        var steps = [];
        if (t.handler_span) steps.push('Handler: ' + formatNanoDuration(t.handler_span.duration_nanos));
        if (t.initial_middleware_span) steps.push('Initial MW: ' + formatNanoDuration(t.initial_middleware_span.duration_nanos));
        if (t.before_middleware_span) steps.push('Before MW: ' + formatNanoDuration(t.before_middleware_span.duration_nanos));
        if (t.on_exit_span) steps.push('onExit: ' + formatNanoDuration(t.on_exit_span.duration_nanos));
        if (t.on_entry_span) steps.push('onEntry: ' + formatNanoDuration(t.on_entry_span.duration_nanos));
        if (t.after_middleware_span) steps.push('After MW: ' + formatNanoDuration(t.after_middleware_span.duration_nanos));
        if (t.terminal_middleware_span) steps.push('Terminal: ' + formatNanoDuration(t.terminal_middleware_span.duration_nanos));
        return '<td>' + (i + 1) + '</td>'
          + '<td>' + (t.from_state ? escHtml(t.from_state) : '\u2205') + ' \u2192 ' + to + '</td>'
          + '<td>' + badge + '</td>'
          + '<td>' + escHtml(t.trigger_name) + '</td>'
          + '<td>' + dur + '</td>'
          + '<td style="font-size:0.85em;color:var(--text-muted);">' + (steps.length ? steps.join(', ') : '\u2014') + '</td>';
      });

    $('#tab-tables').html(html);
  }

  /** Builds a card containing a table with the given header and row renderer. */
  function tableCard(icon, color, title, badgeColor, items, headerRow, rowFn) {
    items = items || [];
    let html = '<div class="card mb-3"><div class="card-header"><h6 class="mb-0">'
      + '<i class="fas ' + icon + '" style="color:' + color + ';"></i> ' + title
      + ' <span class="badge badge-' + badgeColor + '">' + items.length + '</span></h6></div>';

    if (!items.length) {
      html += '<div class="card-body"><p class="text-dim" style="margin:0;">No entries</p></div></div>';
      return html;
    }

    html += '<div class="card-body p-0">'
      + '<table class="table table-sm table-dark-custom mb-0">'
      + '<thead><tr>' + headerRow + '</tr></thead><tbody>';
    items.forEach(function(item, i) {
      html += '<tr>' + rowFn(item, i, items) + '</tr>';
    });
    html += '</tbody></table></div></div>';
    return html;
  }

  // ═══════════════════════════════════════════════
  //  Signal Panel (Send Event)
  // ═══════════════════════════════════════════════

  /** Renders the event injection panel for sending signals to running workflows. */
  function renderSignalPanel(data) {
    if (isTerminalStatus(data.status)) {
      $('#tab-signal').html(
        '<div class="signal-panel"><p class="text-muted-custom">Cannot send events to a '
        + data.status + ' workflow.</p></div>'
      );
      return;
    }

    const validEvents = data.valid_events || {};
    const currentStateEvents = validEvents[data.current_state] || [];
    eventSchemas = {};

    let html = '<div class="signal-panel">'
      + '<h6 style="color:var(--text-secondary);"><i class="fas fa-paper-plane" style="color:var(--color-accent);"></i> Send Event</h6>'
      + '<div class="form-group"><label class="meta-label">Event Type</label>'
      + '<select class="form-control" id="signal-event-select" onchange="onEventSelected()">'
      + '<option value="">-- Select --</option>';

    html += buildEventSelectOptions(validEvents, data.current_state, currentStateEvents);

    html += '</select></div>'
      + '<div id="signal-schema" class="text-muted-custom" style="font-size:0.85em;margin-bottom:8px;"></div>'
      + '<div class="form-group"><label class="meta-label">Payload (JSON)</label>'
      + '<textarea class="form-control" id="signal-payload" rows="5" placeholder="{}"></textarea></div>'
      + '<button class="btn btn-info" onclick="sendSignal()"><i class="fas fa-paper-plane"></i> Send</button>'
      + ' <span id="signal-result" class="signal-result-spacer"></span></div>';

    $('#tab-signal').html(html);
  }

  /** Builds the <optgroup> / <option> HTML for the event type selector. */
  function buildEventSelectOptions(validEvents, currentState, currentStateEvents) {
    let html = '';

    if (currentStateEvents.length) {
      html += '<optgroup label="Valid for ' + escHtml(currentState) + '">';
      currentStateEvents.forEach(function(ev) {
        eventSchemas[ev.event_class_name] = ev;
        html += '<option value="' + escHtml(ev.event_class_name) + '">'
          + escHtml(ev.event_simple_name) + '</option>';
      });
      html += '</optgroup>';
    }

    Object.keys(validEvents).forEach(function(st) {
      if (st === currentState) return;
      const evts = validEvents[st];
      if (!evts.length) return;
      html += '<optgroup label="' + escHtml(st) + ' (not current state)">';
      evts.forEach(function(ev) {
        eventSchemas[ev.event_class_name] = ev;
        html += '<option value="' + escHtml(ev.event_class_name) + '" disabled class="text-dim">'
          + escHtml(ev.event_simple_name) + '</option>';
      });
      html += '</optgroup>';
    });

    return html;
  }

  /** Updates the schema hint and payload template when an event type is selected. */
  function onEventSelected() {
    const eventClass = $('#signal-event-select').val();
    const schema = eventSchemas[eventClass];
    const params = (schema && schema.parameters) || [];

    if (!params.length) {
      $('#signal-schema').html('No parameters required.');
      $('#signal-payload').val('{}');
      return;
    }

    $('#signal-schema').html('Parameters: ' + params.map(function(p) {
      return '<code>' + escHtml(p.name) + ': ' + escHtml(p.type) + '</code>';
    }).join(', '));

    const template = {};
    params.forEach(function(p) { template[p.name] = defaultForType(p.type); });
    $('#signal-payload').val(JSON.stringify(template, null, 2));
  }

  /** Sends an event signal to the current workflow. */
  function sendSignal() {
    const ec = $('#signal-event-select').val();
    if (!ec) {
      alert('Select an event type.');
      return;
    }

    let payload;
    try {
      payload = JSON.parse($('#signal-payload').val());
    } catch (e) {
      alert('Invalid JSON: ' + e.message);
      return;
    }

    if (!confirm('Send ' + ec.split('.').pop() + ' to ' + currentData.workflow_id + '?')) return;

    $('#signal-result').html('<i class="fas fa-spinner fa-spin"></i>');
    $.ajax({
      url: API_BASE + encodeURIComponent(currentData.workflow_id) + '/signal',
      type: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ eventClass: ec, payload: payload })
    }).done(function() {
      $('#signal-result').html('<span style="color:var(--color-state);">Sent! Reloading...</span>');
      setTimeout(function() { loadWorkflow(currentData.workflow_id); }, RELOAD_AFTER_SIGNAL_MS);
    }).fail(function(xhr) {
      let msg = 'Failed to send event';
      try { msg = JSON.parse(xhr.responseText).error || msg; } catch (e) { /* use default */ }
      $('#signal-result').html('<span class="text-error">' + escHtml(msg) + '</span>');
    });
  }

  // ═══════════════════════════════════════════════
  //  Instance Rendering
  // ═══════════════════════════════════════════════

  /**
   * Renders all views for a workflow instance and starts auto-polling if running.
   * This is the main entry point after a successful API fetch.
   */
  function renderInstance(data) {
    stopPolling();
    renderHeader(data);
    renderTimeline(data);
    renderSequenceDiagram(data);
    renderTables(data);
    renderSignalPanel(data);
    $('#instance-view').show();
    showTab('timeline');

    if (!isTerminalStatus(data.status)) {
      pollInterval = setInterval(function() {
        fetchWorkflowJson(data.workflow_id)
          .done(function(fresh) {
            currentData = fresh;
            renderHeader(fresh);
            refreshTimelineData(fresh);
            if (isTerminalStatus(fresh.status)) {
              renderSequenceDiagram(fresh);
              renderTables(fresh);
              renderSignalPanel(fresh);
              stopPolling();
            }
          });
      }, POLL_INTERVAL_MS);
    }
  }

  // ═══════════════════════════════════════════════
  //  Public API (called from HTML onclick handlers)
  // ═══════════════════════════════════════════════

  window.showSearch = showSearch;
  window.searchWorkflow = searchWorkflow;
  window.showTab = showTab;
  window.filterTimeline = filterTimeline;
  window.fitTimeline = fitTimeline;
  window.zoomIn = zoomIn;
  window.zoomOut = zoomOut;
  window.goToStart = goToStart;
  window.goToLatest = goToLatest;
  window.toggleChildren = toggleChildren;
  window.toggleWorkflowJsonPanel = toggleWorkflowJsonPanel;
  window.copyText = copyText;
  window.sendSignal = sendSignal;
  window.onEventSelected = onEventSelected;

  // ═══════════════════════════════════════════════
  //  Initialization
  // ═══════════════════════════════════════════════

  mermaid.initialize({ startOnLoad: false, theme: 'dark' });
  window.addEventListener('hashchange', route);
  $(document).ready(function() { route(); });

})();
</script>
</body>
</html>
