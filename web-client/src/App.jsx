import { useEffect, useMemo, useRef, useState } from 'react'

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
      throw new Error((data && data.error) || text || `HTTP ${response.status}`)
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

async function uploadOne(file, currentPath, folderUpload) {
  const relativeFolder = folderUpload && file.webkitRelativePath
    ? dirname(file.webkitRelativePath)
    : ''
  const mergedPath = [currentPath, relativeFolder].filter(Boolean).join('/')
  const url = `/api/files/upload?path=${encodeURIComponent(mergedPath)}&name=${encodeURIComponent(file.name)}`
  const response = await fetch(url, {
    method: 'PUT',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/octet-stream',
      'X-File-Name': file.name,
    },
    body: file,
  })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `Upload failed: ${file.name}`)
  }
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
  onUp,
  onLoadPath,
  onUploadFiles,
  onUploadFolder,
  onDisconnect,
}) {
  const crumbs = pathCrumbs(path)

  return (
    <section className="drive-layout">
      <aside className="nav-rail glass-card">
        <h3>MediaBus</h3>
        <p>Private Drive</p>
        <div className="nav-group">
          <button className="nav-pill active" onClick={() => onLoadPath('')}>All Files</button>
          <button className="nav-pill" onClick={onUp}>Parent Folder</button>
        </div>
        <div className="rail-foot">Secure local transfer</div>
      </aside>

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
          <label className="btn btn-primary file-btn">
            Upload Files
            <input type="file" multiple onChange={(e) => onUploadFiles(e.currentTarget.files)} />
          </label>
          <label className="btn btn-primary file-btn">
            Upload Folder
            <input
              type="file"
              webkitdirectory=""
              directory=""
              multiple
              onChange={(e) => onUploadFolder(e.currentTarget.files)}
            />
          </label>
          <button className="btn btn-danger" disabled={busy} onClick={onDisconnect}>Disconnect</button>
        </div>

        <div className="status-row">
          <span className="status-tag">Path</span>
          <span className="status-path">/{path}</span>
          <span className="status-log">{log}</span>
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
                      <a className="btn slim" href={`/api/files/download-zip?path=${encodeURIComponent(item.path)}`}>
                        Download ZIP
                      </a>
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
                      <a className="btn slim" href={`/api/files/download?path=${encodeURIComponent(item.path)}`}>
                        Download
                      </a>
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

  const pairPollRef = useRef(null)
  const heartbeatRef = useRef(null)

  const paired = !!boot?.paired

  function clearTimers() {
    if (pairPollRef.current) {
      clearInterval(pairPollRef.current)
      pairPollRef.current = null
    }
    if (heartbeatRef.current) {
      clearInterval(heartbeatRef.current)
      heartbeatRef.current = null
    }
  }

  async function loadPath(nextPath) {
    try {
      const data = await api(`/api/files/list?path=${encodeURIComponent(nextPath || '')}`)
      setPath(data.path || '')
      setItems(data.items || [])
      setLog('')
    } catch (err) {
      setError(err.message || 'Failed to load files')
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
      setError(err.message || 'Failed to bootstrap')
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
      setError(err.message || 'Quick reconnect failed')
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
      setError(err.message || 'Disconnect failed')
    } finally {
      setBusy(false)
    }
  }

  async function uploadFiles(fileList, folderUpload) {
    if (!fileList || fileList.length === 0) return
    setBusy(true)
    setError('')
    try {
      for (const file of fileList) {
        setLog(`Uploading ${file.webkitRelativePath || file.name}`)
        await uploadOne(file, path, folderUpload)
      }
      setLog('Upload complete')
      await loadPath(path)
    } catch (err) {
      setError(err.message || 'Upload failed')
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
        api('/api/heartbeat', { method: 'POST' }).catch(() => {})
      }, 10000)
      return () => clearTimers()
    }

    return undefined
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boot])

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
          <p>Modern local file explorer</p>
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
          onUp={() => loadPath(dirname(path))}
          onLoadPath={loadPath}
          onUploadFiles={(files) => uploadFiles(files, false)}
          onUploadFolder={(files) => uploadFiles(files, true)}
          onDisconnect={disconnect}
        />
      )}
    </main>
  )
}
