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

async function uploadOne(file, currentPath, folderUpload, batch, onProgress) {
  const relativeFolder = folderUpload && file.webkitRelativePath
    ? dirname(file.webkitRelativePath)
    : ''
  const mergedPath = [currentPath, relativeFolder].filter(Boolean).join('/')
  const url = `/api/files/upload?path=${encodeURIComponent(mergedPath)}&name=${encodeURIComponent(file.name)}`
  await new Promise((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open('PUT', url, true)
    request.withCredentials = true
    request.setRequestHeader('Content-Type', 'application/octet-stream')
    request.setRequestHeader('X-File-Name', file.name)
    if (batch?.id) request.setRequestHeader('X-MediaBus-Batch-Id', batch.id)
    if (batch?.totalFiles) request.setRequestHeader('X-MediaBus-Batch-Total', String(batch.totalFiles))
    request.upload.onprogress = (event) => {
      if (!event.lengthComputable || typeof onProgress !== 'function') return
      onProgress(event.loaded, event.total)
    }
    request.onerror = () => reject(new Error('Network error. Upload failed.'))
    request.ontimeout = () => reject(new Error('Upload timed out. Check network and try again.'))
    request.onreadystatechange = () => {
      if (request.readyState !== XMLHttpRequest.DONE) return
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

function PairingView({ boot, busy, onQuickReconnect }) {
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
        {boot.quickPairAvailable && (
          <button className="btn btn-primary" disabled={busy} onClick={onQuickReconnect}>
            Quick Reconnect
          </button>
        )}
      </div>
      <div className="pair-qr-shell glass-card">
        <img className="pair-qr" src={pairQrSrc} alt="Pairing QR" />
      </div>
    </section>
  )
}

function DriveView({
  busy,
  path,
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
  onDisconnect,
}) {
  const crumbs = pathCrumbs(path)

  return (
    <section className="drive-layout">
      <div className="drive-main glass-card">
        <header className="drive-header">
          <div className="breadcrumbs">
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
          <button className="btn" disabled={busy} onClick={onUp}>Up</button>
          <label className="btn btn-primary file-btn" aria-disabled={!permissions.allowUpload}>
            Upload Files
            <input
              type="file"
              multiple
              disabled={busy || !permissions.allowUpload}
              onChange={(e) => onUploadFiles(e.currentTarget.files)}
            />
          </label>
          <label className="btn btn-primary file-btn" aria-disabled={!permissions.allowUpload}>
            Upload Folder
            <input
              type="file"
              webkitdirectory=""
              directory=""
              multiple
              disabled={busy || !permissions.allowUpload}
              onChange={(e) => onUploadFolder(e.currentTarget.files)}
            />
          </label>
          <button className="btn" disabled={busy || !permissions.allowUpload} onClick={onCreateFolder}>
            New Folder
          </button>
          <button className="btn btn-danger" disabled={busy} onClick={onDisconnect}>Disconnect</button>
        </div>

        {log && <div className="status-log-line">{log}</div>}
        <div className={`transfer-progress ${transfer.active ? 'active' : ''}`}>
          <div className="transfer-progress-head">
            <span className="transfer-label">{transfer.label || 'No transfer in progress'}</span>
            <span className="transfer-meta">
              {transfer.active ? `${Math.round(transfer.progress * 100)}%` : ''}
            </span>
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
          <table className="modern-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Modified</th>
                <th className="size-col">Size / Action</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                item.directory ? (
                  <tr key={item.path}>
                    <td>
                      <button className="row-name" onClick={() => onLoadPath(item.path)}>
                        <span className="folder-icon">📁</span>
                        {item.name}
                      </button>
                    </td>
                    <td className="muted-cell">{formatTime(item.lastModified)}</td>
                    <td className="size-cell">
                      <a
                        className={`btn slim ${!permissions.allowDownload ? 'disabled-link' : ''}`}
                        href={permissions.allowDownload ? `/api/files/download-zip?path=${encodeURIComponent(item.path)}` : undefined}
                        onClick={(event) => {
                          if (!permissions.allowDownload) event.preventDefault()
                        }}
                      >
                        Download
                      </a>
                      <button
                        className="btn slim"
                        disabled={busy || !permissions.allowUpload}
                        onClick={() => onRenameItem(item)}
                      >
                        Rename
                      </button>
                      <button
                        className="btn slim btn-danger"
                        disabled={busy || !permissions.allowDelete}
                        onClick={() => onDeleteItem(item)}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ) : (
                  <tr key={item.path}>
                    <td>
                      <div className="row-name static">
                        <span className="file-icon">📄</span>
                        {item.name}
                      </div>
                    </td>
                    <td className="muted-cell">{formatTime(item.lastModified)}</td>
                    <td className="size-cell">
                      <span className="size-value">{formatBytes(item.size)}</span>
                      <a
                        className={`btn slim ${!permissions.allowDownload ? 'disabled-link' : ''}`}
                        href={permissions.allowDownload ? `/api/files/download?path=${encodeURIComponent(item.path)}` : undefined}
                        onClick={(event) => {
                          if (!permissions.allowDownload) event.preventDefault()
                        }}
                      >
                        Download
                      </a>
                      <button
                        className="btn slim"
                        disabled={busy || !permissions.allowUpload}
                        onClick={() => onRenameItem(item)}
                      >
                        Rename
                      </button>
                      <button
                        className="btn slim btn-danger"
                        disabled={busy || !permissions.allowDelete}
                        onClick={() => onDeleteItem(item)}
                      >
                        Delete
                      </button>
                    </td>
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
  }

  async function loadPath(nextPath, options = {}) {
    const { silent = false } = options
    try {
      const data = await api(`/api/files/list?path=${encodeURIComponent(nextPath || '')}`)
      setPath(data.path || '')
      setItems(data.items || [])
      if (!silent) setLog('')
    } catch (err) {
      if (!silent) setError(friendlyErrorMessage(err.message || 'Failed to load files'))
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

  async function quickReconnect() {
    setBusy(true)
    setError('')
    try {
      await api('/api/pair/quick', { method: 'POST' })
      await bootstrap()
    } catch (err) {
      setError(friendlyErrorMessage(err.message || 'Quick reconnect failed'))
    } finally {
      setBusy(false)
    }
  }

  async function disconnect() {
    setBusy(true)
    setError('')
    try {
      await api('/api/session/disconnect', { method: 'POST' })
      await bootstrap()
    } catch (err) {
      setError(friendlyErrorMessage(err.message || 'Disconnect failed'))
    } finally {
      setBusy(false)
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
    const batch = {
      id: (typeof crypto !== 'undefined' && crypto.randomUUID)
        ? crypto.randomUUID()
        : `batch-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      totalFiles: files.length,
    }
    const totalBytes = files.reduce((sum, file) => sum + (file.size || 0), 0)
    const totalFiles = files.length
    const uploadedBytesByName = new Map()
    let uploadedBytes = 0
    let doneFiles = 0
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
        setLog(`Uploading ${file.webkitRelativePath || file.name}`)
        await uploadOne(file, path, folderUpload, batch, (loaded, total) => {
          const safeTotal = total > 0 ? total : file.size || 0
          const currentSent = Math.min(loaded, safeTotal)
          const key = `${file.name}:${file.lastModified}:${file.size}`
          const previousSent = uploadedBytesByName.get(key) || 0
          uploadedBytesByName.set(key, currentSent)
          uploadedBytes += currentSent - previousSent
          const progress = totalBytes > 0 ? Math.min(uploadedBytes / totalBytes, 1) : 0
          setTransfer({
            active: true,
            label: `Uploading ${file.name}`,
            loadedBytes: uploadedBytes,
            totalBytes,
            doneFiles,
            totalFiles,
            progress,
          })
        })
        doneFiles += 1
        const fileProgress = totalBytes > 0 ? Math.min(uploadedBytes / totalBytes, 1) : doneFiles / totalFiles
        setTransfer((prev) => ({
          ...prev,
          doneFiles,
          progress: fileProgress,
        }))
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
      setError(friendlyErrorMessage(err.message || 'Upload failed'))
      setTransfer((prev) => ({
        ...prev,
        active: false,
        label: 'Upload failed',
      }))
    } finally {
      setBusy(false)
    }
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
        <section className="glass-card loading-card">Loading MediaBus Drive...</section>
      </main>
    )
  }

  return (
    <main className="drive-shell">
      <header className="topbar">
        <div>
          <h1>MediaBus Drive</h1>
        </div>
      </header>

      {error && <div className="error-banner">{error}</div>}

      {!paired && boot && (
        <PairingView boot={boot} busy={busy} onQuickReconnect={quickReconnect} />
      )}

      {paired && (
        <DriveView
          busy={busy}
          path={path}
          items={items}
          log={log}
          transfer={transfer}
          permissions={permissions}
          onUp={() => loadPath(dirname(path))}
          onLoadPath={loadPath}
          onUploadFiles={(files) => uploadFiles(files, false)}
          onUploadFolder={(files) => uploadFiles(files, true)}
          onDeleteItem={deleteItem}
          onCreateFolder={createFolder}
          onRenameItem={renameItem}
          onDisconnect={disconnect}
        />
      )}
    </main>
  )
}
