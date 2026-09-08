import React,{useEffect,useState} from 'react';
import {createRoot} from 'react-dom/client';
import './style.css';

const modules=['Patients','Staff','Departments','Appointments','Clinical EHR','Nursing','Admissions','Rooms & Beds','Emergency','Laboratory','Radiology','Pharmacy','Medication','Surgery','Billing','Insurance','Inventory','Blood Bank','Nutrition','Facilities','HR & Shifts','Notifications','Documents','Audit','Reports'];

function App(){
  const [stats,setStats]=useState({});
  const [logged,setLogged]=useState(false);
  const [username,setUsername]=useState('');
  const [password,setPassword]=useState('');

  async function login(event){
    event.preventDefault();
    const r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username,password})});
    if(!r.ok)return;
    const j=await r.json();
    localStorage.setItem('token',j.accessToken);
    setLogged(true);
    load();
  }

  async function load(){
    const t=localStorage.getItem('token');
    if(!t)return;
    const r=await fetch('/api/dashboard',{headers:{Authorization:'Bearer '+t}});
    if(r.ok)setStats(await r.json());
  }

  useEffect(()=>{setLogged(!!localStorage.getItem('token'));load()},[]);

  return <div className="app"><aside><h1>MediCore</h1><p>Hospital Management</p>{modules.map(x=><button key={x}>{x}</button>)}</aside><main><header><div><h2>Operations Dashboard</h2><span>Training build</span></div>{!logged&&<form className="login" onSubmit={login}><input aria-label="Username" type="text" value={username} onChange={event=>setUsername(event.target.value)} autoComplete="username" required/><input aria-label="Password" type="password" value={password} onChange={event=>setPassword(event.target.value)} autoComplete="current-password" required/><button type="submit">Log in</button></form>}</header><section className="cards">{Object.entries(stats).map(([k,v])=><article key={k}><small>{k}</small><strong>{v}</strong></article>)}{!Object.keys(stats).length&&<article><small>Status</small><strong>{logged?'API ready':'Login required'}</strong></article>}</section><section className="panel"><h3>Modules ready</h3><div className="grid">{modules.map((m,i)=><div className="module" key={m}><b>{String(i+1).padStart(2,'0')}</b><span>{m}</span></div>)}</div></section></main></div>
}

createRoot(document.getElementById('root')).render(<App/>);
