import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { changePassword } from '../api/session'
import { ApiError } from '../api/client'

export function Settings() {
  const client=useQueryClient(), navigate=useNavigate()
  const [currentPassword,setCurrent]=useState(''),[newPassword,setNew]=useState(''),[confirmation,setConfirmation]=useState('')
  const [localError,setLocalError]=useState('')
  const change=useMutation({mutationFn:()=>changePassword(currentPassword,newPassword,confirmation),
    onSuccess:async()=>{
      setCurrent('');setNew('');setConfirmation('')
      // Clear private caches exactly as logout does, then explain why reauthentication is needed.
      await client.cancelQueries();client.setQueryData(['session'],null)
      client.removeQueries({predicate:query=>query.queryKey[0]!=='session'})
      // A one-use, non-sensitive notice survives the authentication gate's redirect.
      client.setQueryData(['password-change-notice'],true)
      navigate('/login?passwordChanged=1',{replace:true})
    },
    onError:()=>{setCurrent('');setNew('');setConfirmation('')},
  })
  const fields=change.error instanceof ApiError?change.error.fieldErrors:{}
  function submit(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();setLocalError('')
    if(newPassword!==confirmation){setLocalError('The new passwords do not match.');return}
    if(new TextEncoder().encode(newPassword).length>72){setLocalError('The new password must be at most 72 UTF-8 bytes.');return}
    change.mutate()
  }
  return <div className="form-panel mx-auto"><p className="eyebrow">Your account</p><h1 className="page-title">Account <em>settings</em></h1>
    <section className="card border-0 shadow-sm"><div className="card-body p-4"><h2 className="h5">Change password</h2><p className="text-secondary">After saving, you’ll be signed out of existing sessions. Sign in again with your new password.</p>
      <form onSubmit={submit}>{(localError||change.isError)&&<p className="alert alert-danger" role="alert">{localError||change.error?.message}</p>}<fieldset disabled={change.isPending}>
        {([{id:'currentPassword',label:'Current password',value:currentPassword,set:setCurrent,autocomplete:'current-password'},{id:'newPassword',label:'New password',value:newPassword,set:setNew,autocomplete:'new-password'},{id:'confirmation',label:'Confirm new password',value:confirmation,set:setConfirmation,autocomplete:'new-password'}]).map(field=><div className="mb-3" key={field.id}><label htmlFor={field.id} className="form-label">{field.label}</label><input id={field.id} type="password" className="form-control" value={field.value} onChange={e=>{field.set(e.target.value);setLocalError('');change.reset()}} autoComplete={field.autocomplete} required minLength={field.id==='newPassword'?12:undefined} maxLength={72} aria-invalid={Boolean(fields[field.id])} aria-describedby={field.id+'-help'} /><div id={field.id+'-help'} className={fields[field.id]?'small text-danger':'form-text'}>{fields[field.id]||(field.id==='newPassword'?'12–72 characters, up to 72 UTF-8 bytes. Choose a different password.':'')}</div></div>)}
        <button className="btn btn-dark" type="submit">{change.isPending?'Changing password…':'Change password'}</button>
      </fieldset></form>
    </div></section><p className="small text-secondary mt-3">Looking for display preferences? Choose Comfortable or Compact on the <Link to="/today">Today dashboard</Link>.</p>
  </div>
}
