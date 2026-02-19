import { useEffect, useMemo, useRef, useState } from 'react'

function friendlyErrorMessage(message) {
  const raw = String(message || '').trim()
  const lower = raw.toLowerCase()
  if (lower.includes('read timed out')) {
    return 'Transfer timed out. Check network quality and try again.'
  }
  if (lower.includes('networkerror') || lower.includes('failed to fetch')) {
    return 'Network error. Unable to reach MediaBus right now.'
  }
  if (lower.includes('unauthorized')) {
    return 'Session expired or disconnected. Reconnect to continue.'
  }
  return raw || 'Something went wrong.'
}

function api(path, options = {}) {
  return fetch(path, {
    credentials: 'include',
    ...options,
  }).then(async (response) => {
    const text = await response.text()
    let data = null
    try {
      data = text ? JSON.parse(text) : null
    } catch (_) {
      data = null
    }
    if (!response.ok) {
      throw new Error(friendlyErrorMessage((data && data.error) || text || `HTTP ${response.status}`))
    }
    return data
  })
}

function dirname(path) {
  const parts = (path || '').split('/').filter(Boolean)
  parts.pop()
  return parts.join('/')
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

function formatTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString()
}

function pathCrumbs(path) {
  const segments = (path || '').split('/').filter(Boolean)
  const crumbs = [{ label: 'Root', path: '' }]
  let current = ''
  for (const segment of segments) {
    current = current ? `${current}/${segment}` : segment
    crumbs.push({ label: segment, path: current })
  }
  return crumbs
}

async function uploadOne(file, currentPath, folderUpload, batch, onProgress, onRequestCreated) {
  const relativeFolder = folderUpload && file.webkitRelativePath
    ? dirname(file.webkitRelativePath)
    : ''
  const mergedPath = [currentPath, relativeFolder].filter(Boolean).join('/')
  const url = `/api/files/upload?path=${encodeURIComponent(mergedPath)}&name=${encodeURIComponent(file.name)}`
  await new Promise((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open('PUT', url, true)
    request.withCredentials = true
    request.timeout = 120000
    if (typeof onRequestCreated === 'function') onRequestCreated(request)
    request.setRequestHeader('Content-Type', 'application/octet-stream')
    request.setRequestHeader('X-File-Name', file.name)
    if (batch?.id) request.setRequestHeader('X-MediaBus-Batch-Id', batch.id)
    if (batch?.totalFiles) request.setRequestHeader('X-MediaBus-Batch-Total', String(batch.totalFiles))
    if (batch?.totalBytes) request.setRequestHeader('X-MediaBus-Batch-Bytes', String(batch.totalBytes))
    request.upload.onprogress = (event) => {
      if (!event.lengthComputable || typeof onProgress !== 'function') return
      onProgress(event.loaded, event.total)
    }
    request.onerror = () => reject(new Error('Network error. Upload failed.'))
    request.onabort = () => reject(new Error('Upload was cancelled.'))
    request.ontimeout = () => reject(new Error('Upload timed out. Check network and try again.'))
    request.onload = () => {
      if (request.status >= 200 && request.status < 300) {
        if (typeof onProgress === 'function') onProgress(file.size, file.size)
        resolve()
        return
      }
      reject(new Error(friendlyErrorMessage(request.responseText || `Upload failed: ${file.name}`)))
    }
    request.send(file)
  })
}

function PairingView({ boot }) {
  const pairQrSrc = useMemo(() => {
    if (!boot?.pairQrPayload) return ''
    return `/api/qr?value=${encodeURIComponent(boot.pairQrPayload)}`
  }, [boot])

  return (
    <section className="pair-view">
      <div className="pair-card glass-card">
        <div className="pair-head">
          <h2>Pair This Browser</h2>
          <p>Scan this QR code with the MediaBus host app, or enter the pairing code manually.</p>
        </div>
        <div className="pair-code">{boot.pairCode}</div>
        <div className="pair-meta">Token refreshes automatically until approved.</div>
      </div>
      <div className="pair-qr-shell glass-card">
        <img className="pair-qr" src={pairQrSrc} alt="Pairing QR" />
      </div>
    </section>
  )
}

function DriveView({
  busy,
  pathLoading,
  path,
  canGoUp,
  items,
  log,
  transfer,
  permissions,
  onUp,
  onLoadPath,
  onUploadFiles,
  onUploadFolder,
  onDeleteItem,
  onCreateFolder,
  onRenameItem,
  onCancelUpload,
}) {
  const crumbs = pathCrumbs(path)
  const [isMobile, setIsMobile] = useState(() => {
    if (typeof window === 'undefined') return false
    return window.matchMedia('(max-width: 760px)').matches
  })
  const [openMenuPath, setOpenMenuPath] = useState('')

  useEffect(() => {
    if (typeof window === 'undefined') return undefined
    const media = window.matchMedia('(max-width: 760px)')
    const onChange = () => setIsMobile(media.matches)
    onChange()
    if (typeof media.addEventListener === 'function') {
      media.addEventListener('change', onChange)
      return () => media.removeEventListener('change', onChange)
    }
    media.addListener(onChange)
    return () => media.removeListener(onChange)
  }, [])

  useEffect(() => {
    setOpenMenuPath('')
  }, [path, items.length, isMobile])

  useEffect(() => {
    if (!openMenuPath) return undefined
    const onGlobalPress = (event) => {
      if (event.target instanceof Element && event.target.closest('.mobile-menu-shell')) return
      setOpenMenuPath('')
    }
    const onEscape = (event) => {
      if (event.key === 'Escape') setOpenMenuPath('')
    }
    document.addEventListener('pointerdown', onGlobalPress)
    document.addEventListener('keydown', onEscape)
    return () => {
      document.removeEventListener('pointerdown', onGlobalPress)
      document.removeEventListener('keydown', onEscape)
    }
  }, [openMenuPath])

  function downloadHrefFor(item) {
    if (busy || !permissions.allowDownload) return undefined
    return item.directory
      ? `/api/files/download-zip?path=${encodeURIComponent(item.path)}`
      : `/api/files/download?path=${encodeURIComponent(item.path)}`
  }

  function MobileMenu({ item }) {
    const isOpen = openMenuPath === item.path
    const [openUpward, setOpenUpward] = useState(false)
    const triggerRef = useRef(null)
    const menuRef = useRef(null)

    useEffect(() => {
      if (!isOpen) {
        setOpenUpward(false)
        return
      }
      const trigger = triggerRef.current
      const menu = menuRef.current
      if (!trigger || !menu) return
      const triggerRect = trigger.getBoundingClientRect()
      const menuHeight = menu.offsetHeight || 220
      const spaceBelow = window.innerHeight - triggerRect.bottom
      const spaceAbove = triggerRect.top
      const shouldOpenUpward = spaceBelow < menuHeight + 8 && spaceAbove > spaceBelow
      setOpenUpward(shouldOpenUpward)
    }, [isOpen, item.path])

    return (
      <div className="mobile-menu-shell">
        <button
          ref={triggerRef}
          className="btn slim icon-btn mobile-menu-trigger"
          title={`Options for ${item.name}`}
          aria-label={`Options for ${item.name}`}
          aria-expanded={isOpen}
          onClick={() => setOpenMenuPath((prev) => (prev === item.path ? '' : item.path))}
        >
          <span className="icon-symbol">⋮</span>
        </button>
        {isOpen && (
          <div ref={menuRef} className={`mobile-menu ${openUpward ? 'open-upward' : ''}`}>
            <a
              className={`mobile-menu-item ${busy || !permissions.allowDownload ? 'disabled-link' : ''}`}
              href={downloadHrefFor(item)}
              onClick={(event) => {
                if (busy || !permissions.allowDownload) {
                  event.preventDefault()
                  return
                }
                setOpenMenuPath('')
              }}
            >
              {item.directory ? 'Download folder' : 'Download file'}
            </a>
            <button
              className="mobile-menu-item"
              disabled={busy || !permissions.allowUpload}
              onClick={() => {
                setOpenMenuPath('')
                onRenameItem(item)
              }}
            >
              Rename
            </button>
            <button
              className="mobile-menu-item danger"
              disabled={busy || !permissions.allowDelete}
              onClick={() => {
                setOpenMenuPath('')
                onDeleteItem(item)
              }}
            >
              Delete
            </button>
            <div className="mobile-menu-properties">
              <div><span>Modified</span><strong>{formatTime(item.lastModified)}</strong></div>
              <div><span>Size</span><strong>{item.directory ? '-' : formatBytes(item.size)}</strong></div>
            </div>
          </div>
        )}
      </div>
    )
  }

  const visibleColumnCount = isMobile ? 1 : 4

  return (
    <section className="drive-layout">
      <div className="drive-main glass-card">
        <header className="drive-header">
          <div className="breadcrumbs">
            <button className="crumb-up" title="Up" aria-label="Up" disabled={!canGoUp || busy} onClick={onUp}>
              ↑
            </button>
            {crumbs.map((crumb, index) => (
              <button key={crumb.path || 'root'} className="crumb" onClick={() => onLoadPath(crumb.path)}>
                {crumb.label}
                {index < crumbs.length - 1 ? <span className="sep">/</span> : null}
              </button>
            ))}
          </div>
          <div className="header-right">
            <span className="chip">{items.length} items</span>
          </div>
        </header>

        <div className="toolbar modern-toolbar">
          <label
            className="btn btn-primary file-btn icon-btn control-btn"
            aria-disabled={!permissions.allowUpload}
            title="Upload File"
            aria-label="Upload File"
          >
            <span className="icon-symbol">↑📄</span>
            <span className="control-label">Upload File</span>
            <input
              type="file"
              multiple
              disabled={busy || !permissions.allowUpload}
              onChange={(e) => {
                onUploadFiles(e.currentTarget.files)
                e.currentTarget.value = ''
              }}
            />
          </label>
          <label
            className="btn btn-primary file-btn icon-btn control-btn"
            aria-disabled={!permissions.allowUpload}
            title="Upload Folder"
            aria-label="Upload Folder"
          >
            <span className="icon-symbol">↑📁</span>
            <span className="control-label">Upload Folder</span>
            <input
              type="file"
              webkitdirectory=""
              directory=""
              multiple
              disabled={busy || !permissions.allowUpload}
              onChange={(e) => {
                onUploadFolder(e.currentTarget.files)
                e.currentTarget.value = ''
              }}
            />
          </label>
          <button
            className="btn icon-btn control-btn"
            title="New Folder"
            aria-label="New Folder"
            disabled={busy || !permissions.allowUpload}
            onClick={onCreateFolder}
          >
            <span className="icon-symbol">+📁</span>
            <span className="control-label">New Folder</span>
          </button>
        </div>

        {log && <div className="status-log-line">{log}</div>}
        <div className={`transfer-progress ${transfer.active ? 'active' : ''}`}>
          <div className="transfer-progress-head">
            <span className="transfer-label">{transfer.label || 'No transfer in progress'}</span>
            <div className="transfer-head-actions">
              <span className="transfer-meta">
                {transfer.active ? `${Math.round(transfer.progress * 100)}%` : ''}
              </span>
              {transfer.active && (
                <button className="btn slim btn-danger icon-btn" title="Cancel upload" aria-label="Cancel upload" onClick={onCancelUpload}>
                  <span className="icon-symbol">✕</span>
                </button>
              )}
            </div>
          </div>
          <div className="transfer-track">
            <div
              className="transfer-fill"
              style={{ width: `${Math.round((transfer.progress || 0) * 100)}%` }}
            />
          </div>
          {transfer.active && (
            <div className="transfer-meta-row">
              <span>{transfer.doneFiles}/{transfer.totalFiles} files</span>
              <span>{formatBytes(transfer.loadedBytes)} / {formatBytes(transfer.totalBytes)}</span>
            </div>
          )}
        </div>

        <div className="table-wrap modern-table-wrap">
          <table className={`modern-table ${isMobile ? 'mobile' : ''}`}>
            <thead>
              <tr>
                <th>Name</th>
                {!isMobile && <th>Modified</th>}
                {!isMobile && <th className="size-col">Size</th>}
                {!isMobile && <th className="actions-col">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {pathLoading && items.length === 0 && (
                Array.from({ length: 6 }).map((_, index) => (
                  <tr key={`loading-row-${index}`} className="loading-row">
                    <td colSpan={visibleColumnCount}>
                      <div className="row-loading-skeleton" />
                    </td>
                  </tr>
                ))
              )}
              {items.map((item) => (
                item.directory ? (
                  <tr key={item.path}>
                    <td>
                      <div className="row-main">
                        <button className="row-name" onClick={() => onLoadPath(item.path)}>
                          <span className="folder-icon">📁</span>
                          {item.name}
                        </button>
                        {isMobile && <MobileMenu item={item} />}
                      </div>
                    </td>
                    {!isMobile && <td className="muted-cell">{formatTime(item.lastModified)}</td>}
                    {!isMobile && (
                      <td className="size-cell">
                        <span className="size-value">-</span>
                      </td>
                    )}
                    {!isMobile && (
                      <td className="actions-cell">
                        <div className="actions-row">
                          <a
                            className={`btn slim icon-btn ${busy || !permissions.allowDownload ? 'disabled-link' : ''}`}
                            href={downloadHrefFor(item)}
                            title="Download folder"
                            aria-label="Download folder"
                            onClick={(event) => {
                              if (busy || !permissions.allowDownload) event.preventDefault()
                            }}
                          >
                            <span className="icon-symbol">⬇</span>
                          </a>
                          <button
                            className="btn slim icon-btn"
                            title="Rename"
                            aria-label="Rename"
                            disabled={busy || !permissions.allowUpload}
                            onClick={() => onRenameItem(item)}
                          >
                            <span className="icon-symbol">✎</span>
                          </button>
                          <button
                            className="btn slim btn-danger icon-btn"
                            title="Delete"
                            aria-label="Delete"
                            disabled={busy || !permissions.allowDelete}
                            onClick={() => onDeleteItem(item)}
                          >
                            <span className="icon-symbol">🗑</span>
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                ) : (
                  <tr key={item.path}>
                    <td>
                      <div className="row-main">
                        <div className="row-name static">
                          <span className="file-icon">📄</span>
                          {item.name}
                        </div>
                        {isMobile && <MobileMenu item={item} />}
                      </div>
                    </td>
                    {!isMobile && <td className="muted-cell">{formatTime(item.lastModified)}</td>}
                    {!isMobile && (
                      <td className="size-cell">
                        <span className="size-value">{formatBytes(item.size)}</span>
                      </td>
                    )}
                    {!isMobile && (
                      <td className="actions-cell">
                        <div className="actions-row">
                          <a
                            className={`btn slim icon-btn ${busy || !permissions.allowDownload ? 'disabled-link' : ''}`}
                            href={downloadHrefFor(item)}
                            title="Download file"
                            aria-label="Download file"
                            onClick={(event) => {
                              if (busy || !permissions.allowDownload) event.preventDefault()
                            }}
                          >
                            <span className="icon-symbol">⬇</span>
                          </a>
                          <button
                            className="btn slim icon-btn"
                            title="Rename"
                            aria-label="Rename"
                            disabled={busy || !permissions.allowUpload}
                            onClick={() => onRenameItem(item)}
                          >
                            <span className="icon-symbol">✎</span>
                          </button>
                          <button
                            className="btn slim btn-danger icon-btn"
                            title="Delete"
                            aria-label="Delete"
                            disabled={busy || !permissions.allowDelete}
                            onClick={() => onDeleteItem(item)}
                          >
                            <span className="icon-symbol">🗑</span>
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                )
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}

export default function App() {
  const [boot, setBoot] = useState(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [pathLoading, setPathLoading] = useState(false)
  const [error, setError] = useState('')

  const [path, setPath] = useState('')
  const [items, setItems] = useState([])
  const [log, setLog] = useState('')
  const [transfer, setTransfer] = useState({
    active: false,
    label: '',
    loadedBytes: 0,
    totalBytes: 0,
    doneFiles: 0,
    totalFiles: 0,
    progress: 0,
  })

  const pairPollRef = useRef(null)
  const heartbeatRef = useRef(null)
  const filePollRef = useRef(null)
  const refreshInFlightRef = useRef(false)
  const currentPathRef = useRef('')
  const busyRef = useRef(false)
  const loadRequestSeqRef = useRef(0)
  const activeUploadXhrRef = useRef(null)
  const cancelUploadRef = useRef(false)
  const revealTimerRef = useRef(null)

  const paired = !!boot?.paired
  const permissions = {
    allowUpload: !!boot?.allowUpload,
    allowDownload: !!boot?.allowDownload,
    allowDelete: !!boot?.allowDelete,
  }

  function clearTimers() {
    if (pairPollRef.current) {
      clearInterval(pairPollRef.current)
      pairPollRef.current = null
    }
    if (heartbeatRef.current) {
      clearInterval(heartbeatRef.current)
      heartbeatRef.current = null
    }
    if (filePollRef.current) {
      clearInterval(filePollRef.current)
      filePollRef.current = null
    }
    if (revealTimerRef.current) {
      clearTimeout(revealTimerRef.current)
      revealTimerRef.current = null
    }
  }

  async function loadPath(nextPath, options = {}) {
    const { silent = false } = options
    const requestedPath = nextPath || ''
    // Commit requested path immediately so background refresh does not race
    // and re-request an older directory right after user navigation.
    currentPathRef.current = requestedPath
    const requestSeq = ++loadRequestSeqRef.current
    if (revealTimerRef.current) {
      clearTimeout(revealTimerRef.current)
      revealTimerRef.current = null
    }
    if (!silent) {
      setPath(requestedPath)
      setItems([])
      setPathLoading(true)
    }
    try {
      const data = await api(`/api/files/list?path=${encodeURIComponent(requestedPath)}`)
      if (requestSeq !== loadRequestSeqRef.current) return
      const resolvedPath = data.path || requestedPath
      const nextItems = data.items || []
      setPath(resolvedPath)
      currentPathRef.current = resolvedPath
      if (silent) {
        setItems(nextItems)
      } else {
        let shown = 0
        const step = 24
        const revealNext = () => {
          if (requestSeq !== loadRequestSeqRef.current) return
          shown = Math.min(shown + step, nextItems.length)
          setItems(nextItems.slice(0, shown))
          if (shown < nextItems.length) {
            revealTimerRef.current = setTimeout(revealNext, 16)
          } else {
            revealTimerRef.current = null
          }
        }
        revealNext()
      }
      if (!silent) setLog('')
    } catch (err) {
      if (requestSeq !== loadRequestSeqRef.current) return
      if (!silent) setError(friendlyErrorMessage(err.message || 'Failed to load files'))
    } finally {
      if (!silent && requestSeq === loadRequestSeqRef.current) {
        setPathLoading(false)
      }
    }
  }

  async function bootstrap() {
    clearTimers()
    setLoading(true)
    setError('')
    try {
      const data = await api('/api/bootstrap')
      setBoot(data)
      if (data.paired) {
        await loadPath('')
      }
    } catch (err) {
      setError(friendlyErrorMessage(err.message || 'Failed to bootstrap'))
    } finally {
      setLoading(false)
    }
  }

  async function uploadFiles(fileList, folderUpload) {
    if (!fileList || fileList.length === 0) return
    if (!permissions.allowUpload) {
      setError('Uploads are disabled by host settings.')
      return
    }
    setBusy(true)
    setError('')
    const files = Array.from(fileList)
    const totalBytes = files.reduce((sum, file) => sum + (file.size || 0), 0)
    const totalFiles = files.length
    const batch = {
      id: (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : `batch-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      totalFiles,
      totalBytes,
    }
    const uploadedBytesByName = new Map()
    let uploadedBytes = 0
    let doneFiles = 0
    let lastUiUpdateMs = 0
    cancelUploadRef.current = false
    const updateTransferUi = (label, force = false) => {
      const now = Date.now()
      if (!force && now - lastUiUpdateMs < 80) return
      lastUiUpdateMs = now
      const progress = totalBytes > 0 ? Math.min(uploadedBytes / totalBytes, 1) : 0
      setTransfer({
        active: true,
        label,
        loadedBytes: uploadedBytes,
        totalBytes,
        doneFiles,
        totalFiles,
        progress,
      })
    }
    setTransfer({
      active: true,
      label: 'Preparing upload...',
      loadedBytes: 0,
      totalBytes,
      doneFiles: 0,
      totalFiles,
      progress: 0,
    })
    try {
      for (const file of files) {
        if (cancelUploadRef.current) {
          throw new Error('Upload was cancelled.')
        }
        setLog(`Uploading ${file.webkitRelativePath || file.name}`)
        await uploadOne(file, path, folderUpload, batch, (loaded, total) => {
          const safeTotal = total > 0 ? total : file.size || 0
          const currentSent = Math.min(loaded, safeTotal)
          const key = `${file.name}:${file.lastModified}:${file.size}`
          const previousSent = uploadedBytesByName.get(key) || 0
          uploadedBytesByName.set(key, currentSent)
          uploadedBytes += currentSent - previousSent
          updateTransferUi(`Uploading ${file.name}`)
        }, (request) => {
          activeUploadXhrRef.current = request
        })
        doneFiles += 1
        updateTransferUi(`Uploading ${file.name}`, true)
      }
      setLog('Upload complete')
      setTransfer({
        active: false,
        label: 'Upload complete',
        loadedBytes: totalBytes,
        totalBytes,
        doneFiles: totalFiles,
        totalFiles,
        progress: 1,
      })
      await loadPath(path)
    } catch (err) {
      const message = friendlyErrorMessage(err.message || 'Upload failed')
      if (String(message).toLowerCase().includes('cancel')) {
        setLog('Upload cancelled')
      } else {
        setError(message)
      }
      setTransfer((prev) => ({
        ...prev,
        active: false,
        label: String(message).toLowerCase().includes('cancel') ? 'Upload cancelled' : 'Upload failed',
      }))
    } finally {
      activeUploadXhrRef.current = null
      cancelUploadRef.current = false
      setBusy(false)
    }
  }

  function cancelUpload() {
    cancelUploadRef.current = true
    const xhr = activeUploadXhrRef.current
    if (xhr) {
      try {
        xhr.abort()
      } catch (_) {
      }
    }
  }

  function goUp() {
    const basePath = currentPathRef.current || path || ''
    const parentPath = dirname(basePath)
    if (parentPath === basePath) return
    loadPath(parentPath)
  }

  async function refreshCurrentPath() {
    if (!paired || busyRef.current || refreshInFlightRef.current) return
    refreshInFlightRef.current = true
    try {
      await loadPath(currentPathRef.current, { silent: true })
    } finally {
      refreshInFlightRef.current = false
    }
  }

  async function deleteItem(item) {
    if (!item) return
    if (!permissions.allowDelete) {
      setError('Deletes are disabled by host settings.')
      return
    }
    const ok = window.confirm(`Delete ${item.directory ? 'folder' : 'file'} "${item.name}"?`)
    if (!ok) return
    setBusy(true)
    setError('')
    try {
      await api(`/api/files/delete?path=${encodeURIComponent(item.path)}`, { method: 'DELETE' })
      setLog(`Deleted ${item.name}`)
      await loadPath(path)
    } catch (err) {
      setError(friendlyErrorMessage(err.message || `Delete failed: ${item.name}`))
    } finally {
      setBusy(false)
    }
  }

  async function createFolder() {
    if (!permissions.allowUpload) {
      setError('Folder creation is disabled by host settings.')
      return
    }
    const name = window.prompt('New folder name:')
    if (!name) return
    setBusy(true)
    setError('')
    try {
      await api(
        `/api/files/mkdir?path=${encodeURIComponent(path)}&name=${encodeURIComponent(name)}`,
        { method: 'POST' },
      )
      setLog(`Created folder ${name}`)
      await loadPath(path)
    } catch (err) {
      setError(friendlyErrorMessage(err.message || `Create folder failed: ${name}`))
    } finally {
      setBusy(false)
    }
  }

  async function renameItem(item) {
    if (!item) return
    if (!permissions.allowUpload) {
      setError('Rename is disabled by host settings.')
      return
    }
    const name = window.prompt(`Rename "${item.name}" to:`, item.name)
    if (!name || name === item.name) return
    setBusy(true)
    setError('')
    try {
      await api(
        `/api/files/rename?path=${encodeURIComponent(item.path)}&name=${encodeURIComponent(name)}`,
        { method: 'POST' },
      )
      setLog(`Renamed ${item.name} to ${name}`)
      await loadPath(path)
    } catch (err) {
      setError(friendlyErrorMessage(err.message || `Rename failed: ${item.name}`))
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    bootstrap()
    return () => clearTimers()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    clearTimers()
    if (!boot) return undefined

    if (!boot.paired && boot.pairToken) {
      pairPollRef.current = setInterval(async () => {
        try {
          const status = await api(`/api/pair/status?token=${encodeURIComponent(boot.pairToken)}`)
          if (status.status === 'approved' || status.status === 'not_found') {
            await bootstrap()
          }
        } catch (_) {
        }
      }, 2000)
      return () => clearTimers()
    }

    if (boot.paired) {
      heartbeatRef.current = setInterval(() => {
        if (busyRef.current) return
        api('/api/heartbeat', { method: 'POST' }).catch(async (err) => {
          if (String(err?.message || '').toLowerCase().includes('revoked')) {
            setError('Connection revoked by host.')
          } else {
            setError(friendlyErrorMessage(err?.message || 'Connection issue'))
          }
          await bootstrap()
        })
      }, 3000)
      filePollRef.current = setInterval(() => {
        refreshCurrentPath()
      }, 1200)
      const onVisibleOrFocus = () => {
        refreshCurrentPath()
      }
      document.addEventListener('visibilitychange', onVisibleOrFocus)
      window.addEventListener('focus', onVisibleOrFocus)
      return () => {
        document.removeEventListener('visibilitychange', onVisibleOrFocus)
        window.removeEventListener('focus', onVisibleOrFocus)
        clearTimers()
      }
    }

    return undefined
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boot])

  useEffect(() => {
    currentPathRef.current = path
  }, [path])

  useEffect(() => {
    busyRef.current = busy
  }, [busy])

  if (loading) {
    return (
      <main className="drive-shell">
        <section className="glass-card loading-card">Loading MediaBus...</section>
      </main>
    )
  }

  return (
    <main className="drive-shell">
      <header className="topbar">
        <div>
          <h1>MediaBus</h1>
        </div>
      </header>

      {error && <div className="error-banner">{error}</div>}

      {!paired && boot && (
        <PairingView boot={boot} />
      )}

      {paired && (
        <DriveView
          busy={busy}
          pathLoading={pathLoading}
          path={path}
          canGoUp={(path || '').split('/').filter(Boolean).length > 0}
          items={items}
          log={log}
          transfer={transfer}
          permissions={permissions}
          onUp={goUp}
          onLoadPath={loadPath}
          onUploadFiles={(files) => uploadFiles(files, false)}
          onUploadFolder={(files) => uploadFiles(files, true)}
          onDeleteItem={deleteItem}
          onCreateFolder={createFolder}
          onRenameItem={renameItem}
          onCancelUpload={cancelUpload}
        />
      )}
    </main>
  )
}
