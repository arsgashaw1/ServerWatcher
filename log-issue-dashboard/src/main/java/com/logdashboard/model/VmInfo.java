package com.logdashboard.model;

/**
 * Model class representing VM (Virtual Machine) information for infrastructure management.
 */
public class VmInfo {
    
    private int id;
    private String vmName;
    private String loginUsername;
    private String password;
    private String vmStartCredentialPortal;
    
    public VmInfo() {
    }
    
    public VmInfo(int id, String vmName, String loginUsername, String password, String vmStartCredentialPortal) {
        this.id = id;
        this.vmName = vmName;
        this.loginUsername = loginUsername;
        this.password = password;
        this.vmStartCredentialPortal = vmStartCredentialPortal;
    }
    
    // Getters and Setters
    
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
    }
    
    public String getVmName() {
        return vmName;
    }
    
    public void setVmName(String vmName) {
        this.vmName = vmName;
    }
    
    public String getLoginUsername() {
        return loginUsername;
    }
    
    public void setLoginUsername(String loginUsername) {
        this.loginUsername = loginUsername;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getVmStartCredentialPortal() {
        return vmStartCredentialPortal;
    }
    
    public void setVmStartCredentialPortal(String vmStartCredentialPortal) {
        this.vmStartCredentialPortal = vmStartCredentialPortal;
    }
    
    @Override
    public String toString() {
        return "VmInfo{" +
                "id=" + id +
                ", vmName='" + vmName + '\'' +
                ", loginUsername='" + loginUsername + '\'' +
                ", vmStartCredentialPortal='" + vmStartCredentialPortal + '\'' +
                '}';
    }
}
